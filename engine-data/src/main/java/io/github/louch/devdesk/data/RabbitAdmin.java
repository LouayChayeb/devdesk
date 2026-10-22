package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.queue.Broker;
import io.github.louch.devdesk.core.queue.MessageBroker;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link MessageBroker} for RabbitMQ, over its management HTTP API (the "-management" images publish it on 15672).
 * Plain HTTP and JSON: no AMQP library, and nothing DevDesk does here takes a message out of a queue for good.
 */
public final class RabbitAdmin implements MessageBroker {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            // HTTP/1.1: by default Java first asks a plain-HTTP server to switch to HTTP/2 ("h2c"), which some
            // (RabbitMQ's) mishandle on requests with a body.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Override
    public List<Queue> queues(Broker b) {
        JsonNode list = send(
                b,
                "GET",
                "/api/queues/" + vhost(b) + "?columns=name,messages_ready,messages_unacknowledged,consumers",
                null);
        List<Queue> out = new ArrayList<>();
        for (JsonNode q : list) {
            String name = q.path("name").asString();
            if (!name.startsWith("amq.")) { // RabbitMQ's own (reply queues and the like)
                out.add(new Queue(
                        name,
                        q.path("messages_ready").asLong(0),
                        q.path("messages_unacknowledged").asLong(0),
                        q.path("consumers").asInt(0)));
            }
        }
        out.sort((x, y) -> x.name().compareTo(y.name()));
        return out;
    }

    @Override
    public List<Message> peek(Broker b, String queue, int max) {
        ObjectNode body = JSON.createObjectNode()
                .put("count", max)
                // Take them and put them straight back: the queue keeps every message (they're marked "redelivered").
                .put("ackmode", "ack_requeue_true")
                .put("encoding", "auto")
                .put("truncate", 50_000);
        JsonNode got = send(b, "POST", "/api/queues/" + vhost(b) + "/" + SigV4.encode(queue) + "/get", body.toString());
        List<Message> out = new ArrayList<>();
        int position = 1;
        for (JsonNode m : got) {
            JsonNode props = m.path("properties");
            String payload = m.path("payload").asString();
            if ("base64".equals(m.path("payload_encoding").asString())) {
                payload = "(binary, " + Base64.getDecoder().decode(payload).length + " bytes)";
            }
            Map<String, String> headers = new LinkedHashMap<>();
            props.path("headers")
                    .properties()
                    .forEach(e -> headers.put(
                            e.getKey(), e.getValue().asString(e.getValue().toString())));
            for (String p : List.of("content_type", "message_id", "correlation_id", "reply_to", "type", "app_id")) {
                if (props.has(p)) {
                    headers.put(p, props.path(p).asString());
                }
            }
            Optional<Instant> at = props.has("timestamp")
                    ? Optional.of(Instant.ofEpochSecond(props.path("timestamp").asLong()))
                    : Optional.empty();
            String key = m.path("routing_key").asString();
            out.add(new Message(
                    Integer.toString(position++),
                    at,
                    key.isEmpty() || key.equals(queue) ? Optional.empty() : Optional.of(key),
                    payload,
                    headers));
        }
        return out;
    }

    @Override
    public void publish(Broker b, String queue, String body, Optional<String> key) {
        ObjectNode msg = JSON.createObjectNode()
                // The default exchange delivers to the queue whose name is the routing key: "straight to the queue".
                .put("routing_key", queue)
                .put("payload", body)
                .put("payload_encoding", "string");
        ObjectNode props = msg.putObject("properties");
        props.put("delivery_mode", 2);
        props.put("timestamp", Instant.now().getEpochSecond());
        key.ifPresent(k -> props.putObject("headers").put("key", k));
        JsonNode r = send(b, "POST", "/api/exchanges/" + vhost(b) + "/amq.default/publish", msg.toString());
        if (!r.path("routed").asBoolean(false)) {
            throw new DataException("RabbitMQ took the message but no queue called " + queue + " got it.");
        }
    }

    @Override
    public List<Reader> readers(Broker b, String queue) {
        JsonNode q = send(b, "GET", "/api/queues/" + vhost(b) + "/" + SigV4.encode(queue), null);
        // The name a client gave its connection (connection_name in most client libraries) is on the connection.
        Map<String, String> given = new HashMap<>();
        for (JsonNode c : send(b, "GET", "/api/connections?columns=name,user_provided_name,client_properties", null)) {
            String name = c.path("user_provided_name")
                    .asString(
                            c.path("client_properties").path("connection_name").asString(""));
            given.put(c.path("name").asString(), name);
        }
        List<Reader> out = new ArrayList<>();
        for (JsonNode c : q.path("consumer_details")) {
            JsonNode ch = c.path("channel_details");
            out.add(new Reader(
                    c.path("consumer_tag").asString(),
                    given.getOrDefault(ch.path("connection_name").asString(), ""),
                    ch.path("peer_host").asString() + ":" + ch.path("peer_port").asInt(),
                    -1)); // RabbitMQ doesn't say per consumer how many it holds unacknowledged
        }
        return out;
    }

    /** RabbitMQ's default virtual host is "/", written %2F in its URLs. */
    private static String vhost(Broker b) {
        return SigV4.encode(b.vhost().isEmpty() ? "/" : b.vhost());
    }

    private JsonNode send(Broker b, String method, String path, String body) {
        String auth =
                Base64.getEncoder().encodeToString((b.user() + ":" + b.password()).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://" + b.host() + ":" + b.port() + path))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 401) {
                throw new DataException("RabbitMQ refused the login. DevDesk used RABBITMQ_DEFAULT_USER and"
                        + " RABBITMQ_DEFAULT_PASS from " + b.service()
                        + "'s environment in devdesk.yaml (guest/guest when"
                        + " not set). They only apply when the broker's data is created the first time.");
            }
            if (r.statusCode() == 404) {
                throw new DataException("RabbitMQ has no such queue (or virtual host " + b.vhost() + ").");
            }
            if (r.statusCode() >= 300) {
                throw new DataException("RabbitMQ answered HTTP " + r.statusCode() + ": " + r.body());
            }
            return r.body().isEmpty() ? JSON.createObjectNode() : JSON.readTree(r.body());
        } catch (ConnectException e) {
            throw new DataException(
                    "Can't reach " + b.service() + "'s management API at " + b.host() + ":" + b.port()
                            + ". Is it running? DevDesk needs a \"-management\" image (rabbitmq:4-management) with port 15672"
                            + " published.",
                    e);
        } catch (IOException e) {
            throw new DataException(b.service() + " didn't answer: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataException("interrupted", e);
        }
    }
}
