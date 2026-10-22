package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.SearchIndex;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link SearchIndex} over the HTTP API that Elasticsearch and OpenSearch share for these calls (_cat/indices,
 * _mapping, _search). Only reads: a search never changes the index.
 */
public final class ElasticSearch implements SearchIndex {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            // HTTP/1.1: by default Java first asks a plain-HTTP server to switch to HTTP/2 ("h2c"), which some
            // (RabbitMQ's) mishandle on requests with a body.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Override
    public List<Index> indices(DatabaseTarget t) {
        JsonNode rows = get(t, "/_cat/indices?format=json&bytes=b&h=index,docs.count,store.size,health");
        List<Index> out = new ArrayList<>();
        for (JsonNode r : rows) {
            String name = r.path("index").asString();
            if (!name.startsWith(".")) { // the engine's own (security, tasks, logs of itself)
                out.add(new Index(
                        name,
                        r.path("docs.count").asLong(0),
                        r.path("store.size").asLong(0),
                        r.path("health").asString()));
            }
        }
        out.sort((a, b) -> a.name().compareTo(b.name()));
        return out;
    }

    @Override
    public String mapping(DatabaseTarget t, String index) {
        JsonNode m = get(t, "/" + SigV4.encode(index) + "/_mapping");
        return JSON.writerWithDefaultPrettyPrinter()
                .writeValueAsString(m.path(index).path("mappings"));
    }

    @Override
    public Hits search(DatabaseTarget t, String index, String query, int from, int size) {
        ObjectNode body;
        String q = query.strip();
        if (q.startsWith("{")) {
            try {
                body = (ObjectNode) JSON.readTree(q); // the engine's own query language, as typed
            } catch (RuntimeException e) {
                throw new DataException("that isn't valid JSON: " + e.getMessage());
            }
        } else {
            body = JSON.createObjectNode();
            // "query string" syntax: plain words, or field:value, AND/OR, ranges like total:>100.
            body.set(
                    "query",
                    q.isEmpty()
                            ? JSON.createObjectNode().set("match_all", JSON.createObjectNode())
                            : JSON.createObjectNode()
                                    .set("query_string", JSON.createObjectNode().put("query", q)));
        }
        if (!body.has("from")) {
            body.put("from", from);
        }
        if (!body.has("size")) {
            body.put("size", size);
        }
        JsonNode r = send(t, "POST", "/" + SigV4.encode(index) + "/_search", JSON.writeValueAsString(body));
        List<Hit> hits = new ArrayList<>();
        for (JsonNode h : r.path("hits").path("hits")) {
            hits.add(new Hit(
                    h.path("_id").asString(),
                    h.path("_score").asDouble(0),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(h.path("_source"))));
        }
        JsonNode total = r.path("hits").path("total");
        return new Hits(
                total.path("value").asLong(hits.size()),
                !"gte".equals(total.path("relation").asString()),
                r.path("took").asLong(0),
                hits);
    }

    private JsonNode get(DatabaseTarget t, String path) {
        return send(t, "GET", path, null);
    }

    private JsonNode send(DatabaseTarget t, String method, String path, String body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://" + t.host() + ":" + t.port() + path))
                .timeout(Duration.ofSeconds(30))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (!t.user().isEmpty()) {
            String basic = Base64.getEncoder()
                    .encodeToString((t.user() + ":" + t.password()).getBytes(StandardCharsets.UTF_8));
            b.header("Authorization", "Basic " + basic);
        }
        try {
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json;
            try {
                json = JSON.readTree(r.body());
            } catch (RuntimeException e) {
                json = JSON.createObjectNode();
            }
            if (r.statusCode() >= 300) {
                String reason = json.path("error")
                        .path("root_cause")
                        .path(0)
                        .path("reason")
                        .asString(json.path("error").path("reason").asString(r.body()));
                String hint = r.statusCode() == 401
                        ? " DevDesk used ELASTIC_PASSWORD (user elastic) or OPENSEARCH_INITIAL_ADMIN_PASSWORD (user"
                                + " admin) from " + t.service() + "'s environment in devdesk.yaml."
                        : "";
                throw new DataException(reason + hint);
            }
            return json;
        } catch (ConnectException e) {
            throw new DataException(
                    "Can't reach " + t.service() + " at " + t.host() + ":" + t.port()
                            + ". Is it running? Start it in Services. It can take 20-30 s to start.",
                    e);
        } catch (IOException e) {
            // Elasticsearch 8 has security (and HTTPS) on unless told otherwise; DevDesk speaks plain HTTP.
            throw new DataException(
                    t.service() + " didn't answer plain HTTP (" + e.getMessage() + "). For local"
                            + " development, turn its security off in devdesk.yaml: xpack.security.enabled: \"false\""
                            + " (Elasticsearch) or DISABLE_SECURITY_PLUGIN: \"true\" (OpenSearch).",
                    e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataException("interrupted", e);
        }
    }
}
