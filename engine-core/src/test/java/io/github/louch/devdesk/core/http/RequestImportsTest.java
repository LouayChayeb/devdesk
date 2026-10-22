package io.github.louch.devdesk.core.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class RequestImportsTest {

    /** What NestJS's SwaggerModule publishes, trimmed. */
    private static final String NEST = """
            {
              "openapi": "3.0.0",
              "info": {"title": "Shop API", "version": "1.0"},
              "servers": [{"url": "http://localhost:3001/api"}],
              "paths": {
                "/orders": {
                  "get": {"summary": "List orders", "tags": ["orders"],
                          "parameters": [{"name": "status", "in": "query", "required": true, "schema": {"type": "string", "example": "paid"}},
                                         {"name": "page", "in": "query", "required": false, "schema": {"type": "integer"}}],
                          "security": [{"bearer": []}]},
                  "post": {"operationId": "OrdersController_create", "tags": ["orders"],
                           "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/CreateOrderDto"}}}}}
                },
                "/orders/{id}": {"delete": {"tags": ["orders"], "parameters": [{"name": "id", "in": "path", "required": true}]}}
              },
              "components": {
                "securitySchemes": {"bearer": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"}},
                "schemas": {
                  "CreateOrderDto": {"type": "object", "properties": {
                    "email": {"type": "string", "format": "email"},
                    "items": {"type": "array", "items": {"$ref": "#/components/schemas/Item"}},
                    "id": {"type": "integer", "readOnly": true}}},
                  "Item": {"type": "object", "properties": {"sku": {"type": "string"}, "qty": {"type": "integer", "default": 1}}}
                }
              }
            }
            """;

    @Test
    void nestJsDescriptionBecomesRequests() {
        var d = RequestImports.openApi(NEST, "{{api.url}}");

        assertThat(d.title()).isEqualTo("Shop API");
        assertThat(d.requests())
                .extracting(r -> r.collection() + " | " + r.name() + " | "
                        + r.call().method() + " " + r.call().url())
                .containsExactly(
                        "Shop API / orders | List orders | GET {{api.url}}/api/orders?status=paid",
                        "Shop API / orders | OrdersController_create | POST {{api.url}}/api/orders",
                        "Shop API / orders | DELETE /orders/{id} | DELETE {{api.url}}/api/orders/{{id}}");
        assertThat(d.requests().get(0).call().auth()).isEqualTo(new HttpCall.Auth.Bearer("{{token}}"));
        assertThat(d.requests().get(1).call().text().orElseThrow())
                .contains("\"email\" : \"user@example.com\"")
                .contains("\"sku\" : \"string\"")
                .contains("\"qty\" : 1")
                .doesNotContain("\"id\""); // read-only fields aren't sent
        assertThat(d.requests().get(1).call().headers())
                .containsExactly(new HttpCall.Header("Content-Type", "application/json"));
    }

    @Test
    void postmanFoldersBodiesAndAuth() {
        var list = RequestImports.postman("""
                {"info": {"name": "Shop", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item": [
                   {"name": "Auth", "item": [
                     {"name": "Log in", "request": {"method": "POST", "url": {"raw": "{{base}}/login"},
                       "header": [{"key": "Content-Type", "value": "application/json"}, {"key": "X-Old", "value": "1", "disabled": true}],
                       "body": {"mode": "raw", "raw": "{\\"user\\": \\"ada\\"}"}}}]},
                   {"name": "Upload", "request": {"method": "POST", "url": "{{base}}/files",
                     "auth": {"type": "bearer", "bearer": [{"key": "token", "value": "{{token}}"}]},
                     "body": {"mode": "formdata", "formdata": [{"key": "f", "type": "file", "src": "/tmp/a.txt"}, {"key": "note", "value": "hi"}]}}}
                 ]}
                """);

        assertThat(list).extracting(RequestImports.Imported::collection).containsExactly("Shop / Auth", "Shop");
        assertThat(list.get(0).call().headers()).hasSize(1);
        assertThat(list.get(0).call().text()).contains("{\"user\": \"ada\"}");
        assertThat(list.get(1).call().auth()).isEqualTo(new HttpCall.Auth.Bearer("{{token}}"));
        assertThat(list.get(1).call().body())
                .isEqualTo(new HttpCall.Body.Multipart(
                        List.of(new HttpCall.Field("f", "/tmp/a.txt", true), new HttpCall.Field("note", "hi", false))));
    }

    @Test
    void otherFilesAreRefusedWithAReason() {
        assertThatThrownBy(() -> RequestImports.postman("{\"a\": 1}")).hasMessageContaining("Postman collection");
        assertThatThrownBy(() -> RequestImports.openApi("{\"a\": 1}", "x")).hasMessageContaining("OpenAPI");
        assertThatThrownBy(() -> RequestImports.openApi("<html>", "x")).hasMessageContaining("valid JSON");
    }
}
