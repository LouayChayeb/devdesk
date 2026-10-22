package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.data.SqlEditor.RowChange;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TableChangesParsingTest {

    @Test
    void insertsUpdatesAndDeletesKeepTheirOrderAndNulls() {
        Map<String, Object> values = new HashMap<>();
        values.put("note", null);
        values.put("price", 2);
        var parsed = ApiServer.tableChanges(Map.of(
                        "schema",
                        "public",
                        "table",
                        "items",
                        "changes",
                        List.of(
                                Map.of("op", "insert", "values", Map.of("name", "lamp")),
                                Map.of("op", "update", "key", Map.of("id", 1), "values", values),
                                Map.of("op", "delete", "key", Map.of("id", "2")))))
                .orElseThrow();

        assertThat(parsed.changes())
                .containsExactly(
                        new RowChange.Insert(Map.of("name", Optional.of("lamp"))),
                        new RowChange.Update(
                                Map.of("id", "1"), Map.of("note", Optional.empty(), "price", Optional.of("2"))),
                        new RowChange.Delete(Map.of("id", "2")));
    }

    @Test
    void malformedSavesAreRejected() {
        Map<String, Object> nullKey = new HashMap<>();
        nullKey.put("id", null);
        assertThat(ApiServer.tableChanges(Map.of("schema", "public", "table", "items")))
                .isEmpty();
        assertThat(ApiServer.tableChanges(
                        Map.of("schema", "public", "table", "items", "changes", List.of(Map.of("op", "truncate")))))
                .isEmpty();
        assertThat(ApiServer.tableChanges(Map.of(
                        "schema",
                        "public",
                        "table",
                        "items",
                        "changes",
                        List.of(Map.of("op", "delete", "key", Map.of())))))
                .isEmpty();
        assertThat(ApiServer.tableChanges(Map.of(
                        "schema",
                        "public",
                        "table",
                        "items",
                        "changes",
                        List.of(Map.of("op", "delete", "key", nullKey)))))
                .isEmpty();
        assertThat(ApiServer.tableChanges(Map.of(
                        "schema",
                        "public",
                        "table",
                        "items",
                        "changes",
                        List.of(Map.of(
                                "op", "update", "key", Map.of("id", "1"), "values", Map.of("tags", List.of()))))))
                .isEmpty();
    }
}
