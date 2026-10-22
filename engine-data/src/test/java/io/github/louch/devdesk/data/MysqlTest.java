package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.QueryPlan;
import io.github.louch.devdesk.core.data.SqlBrowser.Filter;
import io.github.louch.devdesk.core.data.SqlBrowser.Op;
import io.github.louch.devdesk.core.data.SqlBrowser.RowQuery;
import io.github.louch.devdesk.core.data.SqlEditor.RowChange;
import io.github.louch.devdesk.core.data.SqlEditor.TableChanges;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Browsing, editing, plans and sessions against real MySQL and MariaDB containers. Skipped without Docker. */
abstract class MysqlTest {

    private static final String[] SETUP = {
        "create table users (id int auto_increment primary key, email varchar(100) not null unique, name text)",
        "create table orders (id int auto_increment primary key, user_id int not null, total decimal(10,2),"
                + " status varchar(20) default 'new', index orders_status (status),"
                + " foreign key (user_id) references users(id))",
        "insert into users(email, name) values ('a@x.test', 'Ada'), ('b@x.test', 'Bob'), ('c@x.test', null)",
        "insert into orders(user_id, total, status) select 1 + (n % 3), n * 1.5, case when n % 4 = 0 then 'failed'"
                + " else 'paid' end from (with recursive s(n) as (select 1 union all select n + 1 from s where n < 30)"
                + " select n from s) x"
    };

    private static TestMysql server;
    private static DatabaseTarget db;
    private final MysqlBrowser browser = new MysqlBrowser();
    private final MysqlEditor editor = new MysqlEditor();

    static void start(String label, String image) throws Exception {
        server = TestMysql.start(label, image, SETUP);
        db = server.target;
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    /** MySQL 9. */
    static class Mysql extends MysqlTest {
        @BeforeAll
        static void up() throws Exception {
            start("mysql", "mysql:9.5");
        }
    }

    /** MariaDB 11. */
    static class Mariadb extends MysqlTest {
        @BeforeAll
        static void up() throws Exception {
            start("mariadb", "mariadb:11");
        }
    }

    @Test
    void tablesStructureAndFilteredRows() {
        assertThat(browser.tables(db))
                .extracting(t -> t.schema() + "." + t.name() + " " + t.estimatedRows())
                .contains("shop.orders 30", "shop.users 3");

        var s = browser.structure(db, "shop", "orders").orElseThrow();
        assertThat(s.columns()).first().satisfies(c -> {
            assertThat(c.primaryKey()).isTrue();
            assertThat(c.defaultValue()).contains("auto_increment");
        });
        assertThat(s.indexes()).anySatisfy(i -> assertThat(i.columns()).containsExactly("status"));
        assertThat(s.foreignKeys())
                .singleElement()
                .satisfies(fk -> assertThat(fk.toTable()).isEqualTo("users"));

        var failed = browser.rows(
                db,
                new RowQuery(
                        "shop",
                        "orders",
                        Optional.of("total"),
                        true,
                        List.of(new Filter("status", Op.EQUALS, "failed")),
                        0,
                        5));
        assertThat(failed.rows())
                .hasSize(5)
                .allSatisfy(r -> assertThat(r.get(3)).isEqualTo("failed"));
        assertThat(failed.more()).isTrue();
        var contains = browser.rows(
                db,
                new RowQuery(
                        "shop",
                        "users",
                        Optional.empty(),
                        false,
                        List.of(new Filter("email", Op.CONTAINS, "B@")),
                        0,
                        5));
        assertThat(contains.rows())
                .singleElement()
                .satisfies(r -> assertThat(r.get(2)).isEqualTo("Bob"));
    }

    @Test
    void statsHaveSizesAndAccountsWithGrants() {
        var stats = browser.stats(db);

        assertThat(stats.tables()).extracting(t -> t.name()).containsExactlyInAnyOrder("orders", "users");
        assertThat(stats.databaseBytes()).isPositive();
        assertThat(stats.roles()).anySatisfy(r -> {
            assertThat(r.name()).startsWith("root@");
            assertThat(r.privileges()).anyMatch(g -> g.startsWith("GRANT"));
        });
    }

    @Test
    void typedSqlIsReadOnly() {
        assertThat(browser.run(db, "select count(*) from orders", 10).rows()).containsExactly(List.of("30"));
        assertThatThrownBy(() -> browser.run(db, "delete from orders", 10))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> browser.run(db, "create table sneaky (a int)", 10))
                .isInstanceOf(DataException.class);
        assertThatThrownBy(() -> browser.run(db, "drop table orders", 10)).isInstanceOf(DataException.class);
    }

    @Test
    void editsAreOneTransaction() throws SQLException {
        editor.apply(
                db,
                new TableChanges(
                        "shop",
                        "users",
                        List.of(
                                new RowChange.Insert(Map.of("email", Optional.of("d@x.test"))),
                                new RowChange.Update(Map.of("id", "3"), Map.of("name", Optional.of("Cy"))))));
        assertThat(rows("select name from users where id = 3")).containsExactly("Cy");

        assertThatThrownBy(() -> editor.apply(
                        db,
                        new TableChanges(
                                "shop",
                                "users",
                                List.of(
                                        new RowChange.Update(Map.of("id", "1"), Map.of("name", Optional.of("Zed"))),
                                        new RowChange.Insert(Map.of("email", Optional.of("a@x.test")))))))
                .hasMessageContaining("Duplicate entry")
                .hasMessageContaining("Nothing was saved");
        assertThat(rows("select name from users where id = 1")).containsExactly("Ada");
        editor.runScript(db, "delete from users where email = 'd@x.test'; update users set name = null where id = 3;");
        assertThat(rows("select count(*) from users")).containsExactly("3");
    }

    @Test
    void planNamesTheTableScan() {
        QueryPlan plan = browser.explain(db, "select * from orders where total > 40", false);
        List<String> steps = new ArrayList<>();
        walk(plan.root(), steps);
        assertThat(steps).anyMatch(x -> x.equals("Table scan orders"));

        QueryPlan analyzed = browser.explain(db, "select * from orders where total > 40", true);
        List<QueryPlan.Node> scans = new ArrayList<>();
        find(analyzed.root(), scans);
        assertThat(scans).anySatisfy(n -> assertThat(n.actualRows()).isPresent());
    }

    @Test
    void sessionsAreListedAndAQueryCanBeStopped() throws Exception {
        var thread = new Thread(() -> {
            try (var c = TestMysql.connect(db);
                    var st = c.createStatement()) {
                st.execute("select sleep(30)");
            } catch (SQLException e) {
                // stopped
            }
        });
        thread.start();
        var sleeping = Optional.<io.github.louch.devdesk.core.data.SqlBrowser.Session>empty();
        for (int i = 0; i < 50 && sleeping.isEmpty(); i++) {
            Thread.sleep(100);
            sleeping = browser.activity(db).stream()
                    .filter(x -> x.query().contains("sleep(30)"))
                    .findFirst();
        }
        assertThat(sleeping).isPresent();
        assertThat(sleeping.get().state()).isEqualTo("active");

        assertThat(editor.stop(db, sleeping.get().pid(), false)).isTrue();
        thread.join(5000);
        assertThat(thread.isAlive()).isFalse();
    }

    private static void walk(QueryPlan.Node n, List<String> out) {
        out.add(n.type() + n.relation().map(r -> " " + r).orElse(""));
        n.children().forEach(c -> walk(c, out));
    }

    private static void find(QueryPlan.Node n, List<QueryPlan.Node> out) {
        if (n.relation().isPresent()) {
            out.add(n);
        }
        n.children().forEach(c -> find(c, out));
    }

    private static List<String> rows(String sql) throws SQLException {
        try (var c = TestMysql.connect(db);
                var st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            List<String> out = new ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
            return out;
        }
    }
}
