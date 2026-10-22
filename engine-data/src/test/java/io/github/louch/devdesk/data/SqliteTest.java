package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.SqlBrowser.Filter;
import io.github.louch.devdesk.core.data.SqlBrowser.Op;
import io.github.louch.devdesk.core.data.SqlBrowser.RowQuery;
import io.github.louch.devdesk.core.data.SqlEditor.RowChange;
import io.github.louch.devdesk.core.data.SqlEditor.TableChanges;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteTest {

    @TempDir
    Path dir;

    private DatabaseTarget db;
    private final SqliteBrowser browser = new SqliteBrowser();
    private final SqliteEditor editor = new SqliteEditor();

    @BeforeEach
    void file() throws Exception {
        Path file = dir.resolve("app.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + file);
                var st = c.createStatement()) {
            st.executeUpdate("create table users (id integer primary key, email text not null unique, name text)");
            st.executeUpdate("create table orders (id integer primary key, user_id integer references users(id),"
                    + " status text default 'new')");
            st.executeUpdate("create index orders_status on orders(status)");
            st.executeUpdate("insert into users(email, name) values ('a@x.test', 'Ada'), ('b@x.test', null)");
            st.executeUpdate("insert into orders(user_id, status) values (1, 'paid'), (1, 'failed'), (2, 'paid')");
        }
        db = new DatabaseTarget("app.db", DatabaseTarget.Kind.SQLITE, "", 0, "", "", file.toString());
    }

    @Test
    void browseAFile() {
        assertThat(browser.tables(db))
                .extracting(t -> t.name() + " " + t.estimatedRows())
                .containsExactly("orders 3", "users 2");
        var s = browser.structure(db, "main", "orders").orElseThrow();
        assertThat(s.indexes()).anySatisfy(i -> assertThat(i.columns()).containsExactly("status"));
        assertThat(s.foreignKeys()).singleElement().satisfies(fk -> {
            assertThat(fk.toTable()).isEqualTo("users");
            assertThat(fk.toColumns()).containsExactly("id");
        });
        var rows = browser.rows(
                db,
                new RowQuery(
                        "main",
                        "orders",
                        Optional.of("id"),
                        true,
                        List.of(new Filter("status", Op.CONTAINS, "PAI")),
                        0,
                        10));
        assertThat(rows.rows()).extracting(r -> r.get(0)).containsExactly("3", "1");
    }

    @Test
    void statsHaveTheFileSizeAndRowsButNoUsers() {
        var stats = browser.stats(db);

        assertThat(stats.databaseBytes()).isPositive();
        assertThat(stats.tables())
                .extracting(t -> t.name() + " " + t.rows())
                .containsExactlyInAnyOrder("orders 3", "users 2");
        assertThat(stats.roles()).isEmpty();
    }

    @Test
    void theSqlEditorCantWrite() {
        assertThatThrownBy(() -> browser.run(db, "delete from orders", 10)).hasMessageContaining("read-only");
        assertThat(browser.run(db, "select count(*) from orders", 10).rows()).containsExactly(List.of("3"));
    }

    @Test
    void editsAndScriptsAreTransactions() {
        editor.apply(
                db,
                new TableChanges(
                        "main",
                        "users",
                        List.of(
                                new RowChange.Update(Map.of("id", "2"), Map.of("name", Optional.of("Bob"))),
                                new RowChange.Insert(Map.of("email", Optional.of("c@x.test"))))));
        assertThat(browser.run(db, "select name from users order by id", 10).rows())
                .containsExactly(List.of("Ada"), List.of("Bob"), java.util.Arrays.asList((String) null));
        // Foreign keys are enforced (SQLite leaves them off unless asked): Ada still has orders.
        assertThatThrownBy(() -> editor.apply(
                        db, new TableChanges("main", "users", List.of(new RowChange.Delete(Map.of("id", "1"))))))
                .hasMessageContaining("FOREIGN KEY");

        assertThatThrownBy(() -> editor.runScript(
                        db, "insert into users(email) values ('c'); insert into users(email) values ('c');"))
                .hasMessageContaining("UNIQUE")
                .hasMessageContaining("statement 2 of 2");
        assertThat(browser.run(db, "select count(*) from users", 10).rows()).containsExactly(List.of("3"));
    }

    @Test
    void planSaysScanOrSearch() {
        var plan = browser.explain(db, "select * from orders where status = 'paid'", false);
        assertThat(plan.root().type()).isEqualTo("Index lookup");
        assertThat(plan.root().relation()).contains("orders");
        assertThat(browser.explain(db, "select * from users where name = 'x'", false)
                        .root()
                        .type())
                .isEqualTo("Table scan");
    }

    @Test
    void aMissingFileIsNotCreated() throws Exception {
        var gone = new DatabaseTarget(
                "x.db",
                DatabaseTarget.Kind.SQLITE,
                "",
                0,
                "",
                "",
                dir.resolve("x.db").toString());
        assertThatThrownBy(() -> browser.tables(gone)).hasMessageContaining("is gone");
        assertThat(Files.exists(dir.resolve("x.db"))).isFalse();
    }
}
