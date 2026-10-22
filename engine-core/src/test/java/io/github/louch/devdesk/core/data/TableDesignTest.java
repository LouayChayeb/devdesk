package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.data.TableDesign.Column;
import io.github.louch.devdesk.core.data.TableDesign.Design;
import io.github.louch.devdesk.core.data.TableDesign.Index;
import io.github.louch.devdesk.core.data.TableDesign.Link;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TableDesignTest {

    private static Column col(String was, String name, String type, boolean nullable, String def, boolean pk) {
        return new Column(Optional.ofNullable(was), name, type, nullable, Optional.ofNullable(def), pk);
    }

    /** orders(id serial pk, user_id int not null → users.id, status text default 'new'), index orders_status. */
    private static final SqlBrowser.Structure ORDERS = new SqlBrowser.Structure(
            List.of(
                    new SqlBrowser.Column(
                            "id", "integer", false, Optional.of("nextval('orders_id_seq'::regclass)"), true),
                    new SqlBrowser.Column("user_id", "integer", false, Optional.empty(), false),
                    new SqlBrowser.Column("status", "text", true, Optional.of("'new'::text"), false)),
            List.of(
                    new SqlBrowser.Index("orders_pkey", List.of("id"), true),
                    new SqlBrowser.Index("orders_status", List.of("status"), false)),
            List.of(new SqlBrowser.ForeignKey(
                    "orders_user_id_fkey", List.of("user_id"), "public", "users", List.of("id"))));

    private static List<Column> unchanged() {
        return List.of(
                col("id", "id", "integer", false, "nextval('orders_id_seq'::regclass)", true),
                col("user_id", "user_id", "integer", false, null, false),
                col("status", "status", "text", true, "'new'::text", false));
    }

    private static Design orders(List<Column> columns) {
        return new Design(
                "public",
                "orders",
                columns,
                List.of(new Index(Optional.of("orders_status"), "orders_status", List.of("status"), false)),
                List.of(new Link(
                        Optional.of("orders_user_id_fkey"),
                        "orders_user_id_fkey",
                        List.of("user_id"),
                        "public",
                        "users",
                        List.of("id"))));
    }

    @Test
    void aNewTableIsOneCreateWithItsKeyLinksThenIndexes() {
        var design = new Design(
                "public",
                "invoices",
                List.of(
                        col(null, "id", "bigserial", false, null, true),
                        col(null, "order_id", "integer", false, null, false),
                        col(null, "total", "numeric(10,2)", true, "0", false)),
                List.of(new Index(Optional.empty(), "invoices_order", List.of("order_id"), true)),
                List.of(new Link(
                        Optional.empty(),
                        "invoices_order_fk",
                        List.of("order_id"),
                        "public",
                        "orders",
                        List.of("id"))));

        assertThat(TableDesign.sql(SqlDialect.POSTGRES, "invoices", Optional.empty(), design))
                .containsExactly(
                        """
                        create table "public"."invoices" (
                          "id" bigserial not null,
                          "order_id" integer not null,
                          "total" numeric(10,2) default 0,
                          primary key ("id"),
                          constraint "invoices_order_fk" foreign key ("order_id") references "public"."orders" ("id")
                        )""", "create unique index \"invoices_order\" on \"public\".\"invoices\" (\"order_id\")");
    }

    @Test
    void nothingChangedMeansNoSql() {
        assertThat(TableDesign.sql(SqlDialect.POSTGRES, "orders", Optional.of(ORDERS), orders(unchanged())))
                .isEmpty();
    }

    @Test
    void postgresRenamesRetypesAndChangesNullAndDefaultColumnByColumn() {
        var columns = List.of(
                unchanged().get(0),
                unchanged().get(1),
                col("status", "state", "varchar(20)", false, null, false),
                col(null, "note", "text", true, null, false));

        // Indexes and links name columns as they will be called: the renamed index column is "state".
        var design = new Design(
                "public",
                "orders",
                columns,
                List.of(new Index(Optional.of("orders_status"), "orders_status", List.of("state"), false)),
                orders(columns).links());

        assertThat(TableDesign.sql(SqlDialect.POSTGRES, "orders", Optional.of(ORDERS), design))
                .containsExactly(
                        "alter table \"public\".\"orders\" rename column \"status\" to \"state\"",
                        "alter table \"public\".\"orders\" alter column \"state\" type varchar(20) using \"state\"::varchar(20)",
                        "alter table \"public\".\"orders\" alter column \"state\" set not null",
                        "alter table \"public\".\"orders\" alter column \"state\" drop default",
                        "alter table \"public\".\"orders\" add column \"note\" text");
    }

    @Test
    void droppedColumnsIndexesAndLinksGoFirstAndARenameLast() {
        var design = new Design(
                "public", "purchases", List.of(unchanged().get(0), unchanged().get(2)), List.of(), List.of());

        assertThat(TableDesign.sql(SqlDialect.POSTGRES, "orders", Optional.of(ORDERS), design))
                .containsExactly(
                        "alter table \"public\".\"orders\" drop constraint \"orders_user_id_fkey\"",
                        "drop index \"public\".\"orders_status\"",
                        "alter table \"public\".\"orders\" drop column \"user_id\"",
                        "alter table \"public\".\"orders\" rename to \"purchases\"");
    }

    @Test
    void mysqlRestatesTheWholeColumnAndUsesItsOwnWords() {
        var columns = List.of(
                unchanged().get(0), unchanged().get(1), col("status", "status", "varchar(20)", true, "'new'", false));
        var design =
                new Design("shop", "orders", columns, List.of(), orders(columns).links());

        assertThat(TableDesign.sql(SqlDialect.MYSQL, "orders", Optional.of(ORDERS), design))
                .containsExactly(
                        "alter table `shop`.`orders` drop index `orders_status`",
                        "alter table `shop`.`orders` modify column `status` varchar(20) default 'new'");
    }

    @Test
    void sqliteSaysWhatItCantDo() {
        var columns =
                List.of(unchanged().get(0), unchanged().get(1), col("status", "status", "integer", true, null, false));

        assertThatThrownBy(() -> TableDesign.sql(SqlDialect.SQLITE, "orders", Optional.of(ORDERS), orders(columns)))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("SQLite can't change a column's type");
    }

    @Test
    void theFormIsCheckedBeforeAnySqlIsWritten() {
        var bad = List.of(col(null, "id", "integer; drop table users", false, null, true));
        assertThatThrownBy(() -> TableDesign.sql(
                        SqlDialect.POSTGRES,
                        "t",
                        Optional.empty(),
                        new Design("public", "t", bad, List.of(), List.of())))
                .hasMessageContaining("isn't a type");
        var nullableKey = List.of(col(null, "id", "integer", true, null, true));
        assertThatThrownBy(() -> TableDesign.sql(
                        SqlDialect.POSTGRES,
                        "t",
                        Optional.empty(),
                        new Design("public", "t", nullableKey, List.of(), List.of())))
                .hasMessageContaining("part of the primary key");
    }

    @Test
    void typeNamesThatMeanTheSameAreNotAChange() {
        assertThat(TableDesign.sameType("character varying(20)", "varchar(20)")).isTrue();
        assertThat(TableDesign.sameType("int", "integer")).isTrue();
        assertThat(TableDesign.sameType("numeric(10, 2)", "decimal(10,2)")).isTrue();
        assertThat(TableDesign.sameType("text", "varchar")).isFalse();
    }
}
