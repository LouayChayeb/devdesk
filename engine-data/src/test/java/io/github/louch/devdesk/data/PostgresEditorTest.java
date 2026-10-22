package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.SqlEditor.RowChange;
import io.github.louch.devdesk.core.data.SqlEditor.TableChanges;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Row edits against a real Postgres in a throwaway container. Skipped when Docker isn't available. */
class PostgresEditorTest {

    private static TestPostgres postgres;
    private static DatabaseTarget db;
    private final PostgresEditor editor = new PostgresEditor();

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = TestPostgres.start("pg-edit", "select 1");
        db = postgres.target;
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void freshTables() throws SQLException {
        sql("""
                drop table if exists items, links, loose;
                create table items (id serial primary key, name text not null unique, price numeric(8,2),
                                    note text, seen timestamptz);
                insert into items(name, price, note) values ('pen', 1.50, 'blue'), ('cup', 4.00, null);
                create table links (a int, b int, label text, primary key (a, b));
                insert into links values (1, 2, 'one-two');
                create table loose (v text);
                """);
    }

    @Test
    void insertUpdateAndDeleteInOneTransaction() throws SQLException {
        var applied = editor.apply(
                db,
                new TableChanges(
                        "public",
                        "items",
                        List.of(
                                new RowChange.Insert(values("name", "lamp", "price", "12.25")),
                                new RowChange.Update(Map.of("id", "1"), values("price", "2", "note", null)),
                                new RowChange.Delete(Map.of("id", "2")))));

        assertThat(applied.rows()).isEqualTo(3);
        // The new row got its id from the serial default; text became numeric; NULL stayed NULL, not "".
        assertThat(rows("select id, name, price, note from items order by id"))
                .containsExactly(List.of("1", "pen", "2.00", "NULL"), List.of("3", "lamp", "12.25", "NULL"));
    }

    @Test
    void oneBadChangeUndoesTheWholeSave() throws SQLException {
        assertThatThrownBy(() -> editor.apply(
                        db,
                        new TableChanges(
                                "public",
                                "items",
                                List.of(
                                        new RowChange.Update(Map.of("id", "1"), values("price", "9")),
                                        new RowChange.Insert(values("name", "pen")))))) // unique name: fails
                .isInstanceOf(DataException.class)
                .hasMessageContaining("duplicate key")
                .hasMessageContaining("change 2 of 2: new row")
                .hasMessageContaining("Nothing was saved");

        assertThat(rows("select price from items where id = 1")).containsExactly(List.of("1.50"));
    }

    @Test
    void aRowThatIsGoneIsReportedNotSilentlySkipped() {
        assertThatThrownBy(() -> editor.apply(
                        db,
                        new TableChanges(
                                "public",
                                "items",
                                List.of(new RowChange.Update(Map.of("id", "99"), values("note", "x"))))))
                .hasMessageContaining("no longer in the table")
                .hasMessageContaining("update of id = 99");
    }

    @Test
    void compositeKeysNeedEveryColumn() throws SQLException {
        editor.apply(
                db,
                new TableChanges(
                        "public",
                        "links",
                        List.of(new RowChange.Update(Map.of("a", "1", "b", "2"), values("label", "x")))));
        assertThat(rows("select label from links")).containsExactly(List.of("x"));

        assertThatThrownBy(() -> editor.apply(
                        db, new TableChanges("public", "links", List.of(new RowChange.Delete(Map.of("a", "1"))))))
                .hasMessageContaining("whole primary key");
    }

    @Test
    void tablesWithoutAPrimaryKeyOnlyTakeNewRows() throws SQLException {
        editor.apply(db, new TableChanges("public", "loose", List.of(new RowChange.Insert(values("v", "hello")))));
        assertThat(rows("select v from loose")).containsExactly(List.of("hello"));

        assertThatThrownBy(() -> editor.apply(
                        db, new TableChanges("public", "loose", List.of(new RowChange.Delete(Map.of("v", "hello"))))))
                .hasMessageContaining("no primary key");
    }

    @Test
    void namesAreCheckedAgainstTheCatalog() {
        assertThatThrownBy(() -> editor.apply(
                        db,
                        new TableChanges(
                                "public",
                                "items",
                                List.of(new RowChange.Update(Map.of("id", "1"), values("note = 'x'; --", "y"))))))
                .hasMessageContaining("no column");
        assertThatThrownBy(() -> editor.apply(db, new TableChanges("public", "items; drop table items", List.of())))
                .hasMessageContaining("there is no table");
    }

    @Test
    void badValuesAreExplainedWithTheChange() {
        assertThatThrownBy(() -> editor.apply(
                        db,
                        new TableChanges(
                                "public",
                                "items",
                                List.of(new RowChange.Update(Map.of("id", "1"), values("price", "cheap"))))))
                .hasMessageContaining("invalid input syntax for type numeric")
                .hasMessageContaining("update of id = 1");
    }

    @Test
    void aScriptRunsAsOneTransaction() throws SQLException {
        var applied = editor.runScript(db, """
                insert into items(name, price) values ('a;b', 1);
                insert into items(name, price) values ('c', 2);
                update items set note = 'x' where name = 'pen';
                """);
        assertThat(applied.rows()).isEqualTo(3);
        assertThat(rows("select count(*) from items")).containsExactly(List.of("4"));

        assertThatThrownBy(() -> editor.runScript(db, """
                        insert into items(name) values ('d');
                        insert into items(name) values ('pen');
                        """))
                .hasMessageContaining("duplicate key")
                .hasMessageContaining("Nothing was imported");
        assertThat(rows("select count(*) from items where name = 'd'")).containsExactly(List.of("0"));
    }

    /** Pairs of column, value; a null value means SQL NULL. Keeps the order, like the grid. */
    private static Map<String, Optional<String>> values(String... pairs) {
        Map<String, Optional<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], Optional.ofNullable(pairs[i + 1]));
        }
        return m;
    }

    private static void sql(String sql) throws SQLException {
        try (Connection c = TestPostgres.connect(db);
                Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<List<String>> rows(String sql) throws SQLException {
        try (Connection c = TestPostgres.connect(db);
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            List<List<String>> out = new ArrayList<>();
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= n; i++) {
                    row.add(rs.getString(i) == null ? "NULL" : rs.getString(i));
                }
                out.add(row);
            }
            return out;
        }
    }
}
