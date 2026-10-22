package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryLogTest {

    private static final Instant T = Instant.parse("2026-10-08T11:00:00Z");

    private static List<QueryLog.Line> lines(String... texts) {
        return List.of(texts).stream().map(t -> new QueryLog.Line(T, t)).toList();
    }

    @Test
    void statementsPreparedQueriesAndTheirParametersAreRead() {
        var queries = QueryLog.parse(lines(
                "2026-10-08 11:00:00.100 UTC [40] LOG:  duration: 0.120 ms  parse <unnamed>: select * from orders where id = $1",
                "2026-10-08 11:00:00.101 UTC [40] LOG:  duration: 0.030 ms  bind <unnamed>: select * from orders where id = $1",
                "2026-10-08 11:00:00.102 UTC [40] LOG:  duration: 0.412 ms  execute <unnamed>: select * from orders where id = $1",
                "2026-10-08 11:00:00.102 UTC [40] DETAIL:  Parameters: $1 = '7'",
                "2026-10-08 11:00:00.200 UTC [41] LOG:  duration: 2.5 ms  statement: insert into orders(user_id, total)",
                "\tvalues (1, 9.5) returning id",
                "2026-10-08 11:00:00.300 UTC [41] LOG:  checkpoint starting: time"));

        assertThat(queries)
                .extracting(q -> q.ms() + " " + q.sql() + " " + q.params())
                .containsExactly(
                        "0.412 select * from orders where id = $1 $1 = '7'",
                        "2.5 insert into orders(user_id, total)\nvalues (1, 9.5) returning id ");
    }

    @Test
    void theCreatedRowIsTheFirstInsertWithTheIdTheAnswerGave() {
        var queries = List.of(
                new QueryLog.Query(T, 1, "insert into orders(user_id) values (1) returning id", ""),
                new QueryLog.Query(T, 1, "insert into order_items values (8912, 3, 1)", ""),
                new QueryLog.Query(T, 1, "update products set stock = stock - 1 where id = $1", "$1 = '3'"),
                new QueryLog.Query(T, 1, "delete from \"carts\" where id = 5", ""));

        assertThat(QueryLog.rows(queries, "{\"queued\": true, \"id\": 8912}"))
                .containsExactly(
                        new QueryLog.RowLink("public", "orders", "id", "8912", "created"),
                        new QueryLog.RowLink("public", "products", "id", "3", "changed"),
                        new QueryLog.RowLink("public", "carts", "id", "5", "deleted"));
        assertThat(QueryLog.rows(queries.subList(0, 1), "not json")).isEmpty();
    }
}
