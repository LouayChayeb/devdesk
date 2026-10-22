package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.data.DatabaseTarget;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Against a real Elasticsearch (security off, as for local development). */
@Tag("docker")
class ElasticSearchTest {

    private static TestContainer server;
    private static DatabaseTarget target;
    private final ElasticSearch es = new ElasticSearch();

    @BeforeAll
    static void start() throws Exception {
        server = TestContainer.start(
                "es",
                "docker.elastic.co/elasticsearch/elasticsearch:8.19.4",
                9200,
                port -> call("GET", port, "/_cluster/health?wait_for_status=yellow&timeout=1s", null) == 200,
                "discovery.type=single-node",
                "xpack.security.enabled=false",
                "ES_JAVA_OPTS=-Xms512m -Xmx512m");
        target = new DatabaseTarget("search", DatabaseTarget.Kind.SEARCH, "127.0.0.1", server.port, "", "", "");
        call(
                "PUT",
                server.port,
                "/orders/_doc/1?refresh=true",
                "{\"status\":\"paid\",\"total\":120,\"item\":\"desk lamp\"}");
        call(
                "PUT",
                server.port,
                "/orders/_doc/2?refresh=true",
                "{\"status\":\"open\",\"total\":15,\"item\":\"notebook\"}");
        call(
                "PUT",
                server.port,
                "/orders/_doc/3?refresh=true",
                "{\"status\":\"paid\",\"total\":40,\"item\":\"coffee mug\"}");
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    private static int call(String method, int port, String path, String body) {
        try {
            var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Content-Type", "application/json")
                    .method(
                            method,
                            body == null
                                    ? HttpRequest.BodyPublishers.noBody()
                                    : HttpRequest.BodyPublishers.ofString(body));
            return HttpClient.newHttpClient()
                    .send(b.build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    @Test
    void indicesAndMapping() {
        assertThat(es.indices(target)).anySatisfy(i -> {
            assertThat(i.name()).isEqualTo("orders");
            assertThat(i.documents()).isEqualTo(3);
        });
        assertThat(es.mapping(target, "orders")).contains("\"total\"", "\"long\"");
    }

    @Test
    void queryStringAndJson() {
        var paid = es.search(target, "orders", "status:paid AND total:>100", 0, 20);
        assertThat(paid.total()).isEqualTo(1);
        assertThat(paid.hits().getFirst().id()).isEqualTo("1");
        assertThat(paid.hits().getFirst().source()).contains("desk lamp");

        assertThat(es.search(target, "orders", "", 0, 20).total()).isEqualTo(3);
        var json = es.search(target, "orders", "{\"query\": {\"match\": {\"item\": \"mug\"}}}", 0, 20);
        assertThat(json.hits()).extracting(h -> h.id()).containsExactly("3");
    }

    @Test
    void mistakesAreExplained() {
        assertThatThrownBy(() -> es.search(target, "nope", "", 0, 5)).hasMessageContaining("no such index");
        assertThatThrownBy(() -> es.search(target, "orders", "{broken", 0, 5)).hasMessageContaining("valid JSON");
    }
}
