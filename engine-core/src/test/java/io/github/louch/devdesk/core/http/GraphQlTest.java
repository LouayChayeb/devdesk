package io.github.louch.devdesk.core.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GraphQlTest {

    /** What a server answers to the introspection query, cut down to two types. */
    private static final String SCHEMA = """
            {"data": {"__schema": {
              "queryType": {"name": "Query"}, "mutationType": {"name": "Mutation"},
              "types": [
                {"kind": "OBJECT", "name": "Query", "fields": [
                  {"name": "order", "args": [{"name": "id", "type": {"kind": "NON_NULL", "name": null, "ofType": {"kind": "SCALAR", "name": "ID"}}}],
                   "type": {"kind": "OBJECT", "name": "Order", "ofType": null}},
                  {"name": "orders", "args": [],
                   "type": {"kind": "NON_NULL", "name": null, "ofType": {"kind": "LIST", "name": null, "ofType": {"kind": "OBJECT", "name": "Order"}}}}]},
                {"kind": "OBJECT", "name": "Mutation", "fields": [
                  {"name": "cancelOrder", "args": [{"name": "id", "type": {"kind": "SCALAR", "name": "ID"}}],
                   "type": {"kind": "SCALAR", "name": "Boolean", "ofType": null}}]},
                {"kind": "OBJECT", "name": "Order", "fields": [
                  {"name": "id", "args": [], "type": {"kind": "SCALAR", "name": "ID"}},
                  {"name": "total", "args": [], "type": {"kind": "SCALAR", "name": "Float"}},
                  {"name": "user", "args": [], "type": {"kind": "OBJECT", "name": "User"}}]},
                {"kind": "OBJECT", "name": "User", "fields": [
                  {"name": "name", "args": [], "type": {"kind": "SCALAR", "name": "String"}}]}
              ]}}}
            """;

    @Test
    void operationsComeWithTypesAndAPasteableExample() {
        var ops = GraphQl.operations(SCHEMA);

        assertThat(ops)
                .extracting(o -> o.kind() + " " + o.name() + " " + o.args() + " → " + o.returns())
                .containsExactly(
                        "query order [id: ID!] → Order",
                        "query orders [] → [Order]!",
                        "mutation cancelOrder [id: ID] → Boolean");
        assertThat(ops.getFirst().example())
                .isEqualTo("query Order($id: ID!) {\n  order(id: $id) { id total user { name } }\n}");
        assertThat(ops.get(2).example()).isEqualTo("mutation CancelOrder($id: ID) {\n  cancelOrder(id: $id)\n}");
    }

    @Test
    void thePayloadIsOneJsonObjectAndBadVariablesAreSaidPlainly() {
        assertThat(GraphQl.payload("{ orders { id } }", "")).isEqualTo("{\"query\":\"{ orders { id } }\"}");
        assertThat(GraphQl.payload("query($id: ID)", "{\"id\": 7}"))
                .isEqualTo("{\"query\":\"query($id: ID)\",\"variables\":{\"id\":7}}");
        assertThatThrownBy(() -> GraphQl.payload("q", "[1]")).hasMessageContaining("must be a JSON object");
        assertThatThrownBy(() -> GraphQl.operations("{\"errors\": []}")).hasMessageContaining("introspection");
    }
}
