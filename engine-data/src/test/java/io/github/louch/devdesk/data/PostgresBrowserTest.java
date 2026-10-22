package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.SqlBrowser.Filter;
import io.github.louch.devdesk.core.data.SqlBrowser.Op;
import io.github.louch.devdesk.core.data.SqlBrowser.RowQuery;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Against a real Postgres in a throwaway container (removed afterwards). Skipped when Docker isn't available. */
class PostgresBrowserTest {

    private static TestPostgres postgres;
    private static DatabaseTarget db;
    private final PostgresBrowser browser = new PostgresBrowser();

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = TestPostgres.start("pg", """
                            create table users (id serial primary key, email text not null unique, name text);
                            create table orders (id serial primary key, user_id int not null references users(id),
                                                 total numeric(10,2), status text default 'new');
                            create index orders_status on orders(status);
                            insert into users(email, name) values ('a@x.test', 'Ada'), ('b@x.test', 'Bob'),
                                                                   ('c@x.test', null);
                            insert into orders(user_id, total, status)
                                select 1 + (n % 3), n * 1.5, case when n % 4 = 0 then 'failed' else 'paid' end
                                from generate_series(1, 30) n;
                            create view big_orders as select * from orders where total > 30;
                            -- An update writes a new row version at the end of the table: unsorted, Ada comes last.
                            update users set name = name where id = 1;
                            """);
        db = postgres.target;
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void tablesAndViewsWithExactCounts() {
        assertThat(browser.tables(db))
                .extracting(t -> t.schema() + "." + t.name() + " " + (t.view() ? "view" : t.estimatedRows()))
                .containsExactly("public.big_orders view", "public.orders 30", "public.users 3");
    }

    @Test
    void statsHaveSizesScanCountsAndRoles() {
        var stats = browser.stats(db);

        assertThat(stats.databaseBytes()).isPositive();
        assertThat(stats.tables())
                .extracting(t -> t.schema() + "." + t.name())
                .containsExactlyInAnyOrder("public.orders", "public.users");
        var orders = stats.tables().stream()
                .filter(t -> t.name().equals("orders"))
                .findFirst()
                .orElseThrow();
        assertThat(orders.totalBytes()).isGreaterThan(orders.indexBytes()).isPositive();
        assertThat(orders.seqScans()).isNotNegative();
        assertThat(stats.roles())
                .anySatisfy(r -> {
                    assertThat(r.name()).isEqualTo("postgres");
                    assertThat(r.attributes()).contains("superuser", "can log in");
                })
                .noneSatisfy(r -> assertThat(r.name()).startsWith("pg_"));
    }

    @Test
    void structureShowsKeysIndexesAndLinks() {
        var s = browser.structure(db, "public", "orders").orElseThrow();

        assertThat(s.columns())
                .extracting(c ->
                        c.name() + " " + c.type() + (c.primaryKey() ? " pk" : "") + (c.nullable() ? "" : " not null"))
                .containsExactly(
                        "id integer pk not null", "user_id integer not null", "total numeric(10,2)", "status text");
        assertThat(s.indexes()).anySatisfy(i -> {
            assertThat(i.name()).isEqualTo("orders_status");
            assertThat(i.columns()).containsExactly("status");
        });
        assertThat(s.foreignKeys()).singleElement().satisfies(fk -> {
            assertThat(fk.columns()).containsExactly("user_id");
            assertThat(fk.toTable()).isEqualTo("users");
            assertThat(fk.toColumns()).containsExactly("id");
        });
        assertThat(browser.structure(db, "public", "nope")).isEmpty();
    }

    @Test
    void rowsAreFilteredSortedAndPaged() {
        var failed = browser.rows(
                db,
                new RowQuery(
                        "public",
                        "orders",
                        Optional.of("total"),
                        true,
                        List.of(new Filter("status", Op.EQUALS, "failed")),
                        0,
                        5));

        assertThat(failed.columns()).containsExactly("id", "user_id", "total", "status");
        assertThat(failed.rows())
                .hasSize(5)
                .allSatisfy(r -> assertThat(r.get(3)).isEqualTo("failed"));
        assertThat(failed.rows().getFirst().get(2)).isEqualTo("42.00"); // n = 28, sorted by total, biggest first
        assertThat(failed.more()).isTrue(); // 7 failed orders, 5 per page

        var numbers = browser.rows(
                db,
                new RowQuery(
                        "public",
                        "orders",
                        Optional.of("id"),
                        false,
                        List.of(new Filter("id", Op.GREATER, "27")),
                        0,
                        10));
        assertThat(numbers.rows()).extracting(r -> r.get(0)).containsExactly("28", "29", "30"); // numbers, not text

        var nulls = browser.rows(
                db,
                new RowQuery(
                        "public",
                        "users",
                        Optional.empty(),
                        false,
                        List.of(new Filter("name", Op.IS_NULL, "")),
                        0,
                        10));
        assertThat(nulls.rows())
                .singleElement()
                .satisfies(r -> assertThat(r.get(2)).isNull());
    }

    @Test
    void rowsWithoutASortFollowThePrimaryKey() {
        var users = browser.rows(db, new RowQuery("public", "users", Optional.empty(), false, List.of(), 0, 10));

        // Without the default "order by id", the updated row 1 would come last and could change pages.
        assertThat(users.rows()).extracting(r -> r.get(0)).containsExactly("1", "2", "3");
    }

    @Test
    void typedSqlRunsAndWritesAreRefusedByPostgresItself() {
        var r = browser.run(db, "select status, count(*) from orders group by status order by status", 100);
        assertThat(r.rows()).containsExactly(List.of("failed", "7"), List.of("paid", "23"));

        assertThatThrownBy(() -> browser.run(db, "delete from orders", 100))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("read-only");
        assertThat(browser.run(db, "select count(*) from orders", 1).rows().getFirst())
                .containsExactly("30");
    }

    @Test
    void namesFromTheUserNeverReachSqlUnchecked() {
        assertThatThrownBy(() -> browser.rows(
                        db,
                        new RowQuery("public", "orders; drop table users", Optional.empty(), false, List.of(), 0, 10)))
                .isInstanceOf(DataException.class);
        assertThatThrownBy(() -> browser.rows(
                        db,
                        new RowQuery(
                                "public", "orders", Optional.of("id desc; drop table users"), false, List.of(), 0, 10)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("has no column");
        assertThat(browser.tables(db)).anySatisfy(t -> assertThat(t.name()).isEqualTo("users"));
    }

    @Test
    void wrongPasswordAndNothingListeningAreExplained() {
        var wrong = new DatabaseTarget(
                "postgres", DatabaseTarget.Kind.POSTGRES, "127.0.0.1", db.port(), "postgres", "nope", "shop");
        assertThatThrownBy(() -> browser.tables(wrong))
                .hasMessageContaining("password authentication failed")
                .hasMessageContaining("POSTGRES_PASSWORD");

        var down =
                new DatabaseTarget("postgres", DatabaseTarget.Kind.POSTGRES, "127.0.0.1", 1, "postgres", "dev", "shop");
        assertThatThrownBy(() -> browser.tables(down)).hasMessageContaining("Is it running?");
    }

    @Test
    void identifiersAreQuotedSafely() {
        assertThat(PostgresBrowser.quote("my\"table")).isEqualTo("\"my\"\"table\"");
    }

    @Test
    void planShowsTheTreeAndWithAnalyzeWhatReallyHappened() {
        var plan = browser.explain(
                db, "select * from orders o join users u on u.id = o.user_id where o.total > 10;", true);

        assertThat(plan.executionMs()).isPresent();
        assertThat(plan.raw()).startsWith("[");
        List<String> types = new java.util.ArrayList<>();
        walk(plan.root(), types);
        assertThat(types).anyMatch(t -> t.contains("Join"));
        assertThat(plan.root().actualRows()).contains(24L); // total = n * 1.5 > 10 for n = 7..30

        assertThatThrownBy(() -> browser.explain(db, "explain select 1", false)).hasMessageContaining("leave out");
        // ANALYZE runs the query, but in the read-only session: a delete fails instead of deleting.
        assertThatThrownBy(() -> browser.explain(db, "delete from orders", true))
                .hasMessageContaining("read-only");
    }

    private static void walk(io.github.louch.devdesk.core.data.QueryPlan.Node n, List<String> types) {
        types.add(n.type() + n.relation().map(r -> " " + r).orElse(""));
        n.children().forEach(c -> walk(c, types));
    }

    @Test
    void activityListsOtherSessionsAndOneCanBeStopped() throws Exception {
        var thread = new Thread(() -> {
            try (var c = TestPostgres.connect(db);
                    var st = c.createStatement()) {
                st.execute("select pg_sleep(30)");
            } catch (java.sql.SQLException e) {
                // cancelled: expected
            }
        });
        thread.start();
        var sleeping = waitFor(() -> browser.activity(db).stream()
                .filter(x -> x.query().contains("pg_sleep(30)") && x.state().equals("active"))
                .findFirst());
        assertThat(sleeping.running()).isPresent();

        assertThat(new PostgresEditor().stop(db, sleeping.pid(), false)).isTrue();
        thread.join(5000);
        assertThat(thread.isAlive()).isFalse();
        assertThat(new PostgresEditor().stop(db, 999_999, false)).isFalse();
    }

    private static <T> T waitFor(java.util.function.Supplier<Optional<T>> check) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Optional<T> v = check.get();
            if (v.isPresent()) {
                return v.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("never happened");
    }

    @Test
    void everyMatchingRowIsStreamedForExportWithWholeValues() {
        List<List<String>> rows = new java.util.ArrayList<>();
        List<String> names = new java.util.ArrayList<>();
        browser.each(
                db,
                new RowQuery(
                        "public",
                        "orders",
                        Optional.of("id"),
                        false,
                        List.of(new Filter("status", Op.EQUALS, "failed")),
                        3,
                        1),
                new io.github.louch.devdesk.core.data.SqlBrowser.RowSink() {
                    @Override
                    public void columns(List<String> n, List<String> types) {
                        names.addAll(n);
                    }

                    @Override
                    public void row(List<String> values) {
                        rows.add(values);
                    }
                });

        assertThat(names).containsExactly("id", "user_id", "total", "status");
        assertThat(rows).hasSize(7); // offset and limit are ignored: every failed order
        assertThat(rows.getFirst().get(0)).isEqualTo("4");
    }
}
