package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.data.SqlBrowser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowQueryParsingTest {

    @Test
    void aFullRequestBecomesARowQueryWithTheLimitCapped() {
        var q = ApiServer.rowQuery(Map.of(
                        "schema",
                        "public",
                        "table",
                        "orders",
                        "orderBy",
                        "total",
                        "descending",
                        true,
                        "filters",
                        List.of(Map.of("column", "status", "op", "equals", "value", "failed")),
                        "offset",
                        200,
                        "limit",
                        1_000_000))
                .orElseThrow();

        assertThat(q.table()).isEqualTo("orders");
        assertThat(q.orderBy()).contains("total");
        assertThat(q.descending()).isTrue();
        assertThat(q.filters()).containsExactly(new SqlBrowser.Filter("status", SqlBrowser.Op.EQUALS, "failed"));
        assertThat(q.offset()).isEqualTo(200);
        assertThat(q.limit()).isEqualTo(ApiServer.MAX_ROWS);
    }

    @Test
    void unknownOperatorsAndMissingNamesAreRejected() {
        assertThat(ApiServer.rowQuery(Map.of("schema", "public"))).isEmpty();
        assertThat(ApiServer.rowQuery(Map.of(
                        "schema", "public",
                        "table", "orders",
                        "filters", List.of(Map.of("column", "id", "op", "; drop table", "value", "1")))))
                .isEmpty();
    }
}
