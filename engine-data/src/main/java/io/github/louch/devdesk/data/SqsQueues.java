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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link MessageBroker} for Amazon SQS's API, as local stand-ins speak it (ElasticMQ, LocalStack): JSON requests
 * signed like S3's ({@link SigV4}). Peeking asks for messages with a zero "visibility timeout", so they stay
 * available to the app at once.
 */
public final class SqsQueues implements MessageBroker {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REGION = "us-east-1";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Clock clock;

    public SqsQueues(Clock clock) {
        this.clock = clock;
    }

    @Override
    public List<Queue> queues(Broker b) {
        List<Queue> out = new ArrayList<>();
        for (JsonNode url : call(b, "ListQueues", JSON.createObjectNode()).path("QueueUrls")) {
            JsonNode attrs = attributes(b, url.asString());
            out.add(new Queue(
                    name(url.asString()),
                    attrs.path("ApproximateNumberOfMessages").asLong(0),
                    attrs.path("ApproximateNumberOfMessagesNotVisible").asLong(0),
                    0)); // SQS doesn't know who polls it
        }
        out.sort((x, y) -> x.name().compareTo(y.name()));
        return out;
    }

    @Override
    public List<Message> peek(Broker b, String queue, int max) {
        String url = url(b, queue);
        Map<String, Message> seen = new LinkedHashMap<>();
        // SQS hands out at most 10 per call, picked by the server: a few calls gather up to max different ones.
        for (int round = 0; round < Math.max(1, max / 10) + 2 && seen.size() < max; round++) {
            ObjectNode req = JSON.createObjectNode()
                    .put("QueueUrl", url)
                    .put("MaxNumberOfMessages", Math.min(10, max))
                    .put("VisibilityTimeout", 0) // looked at, not hidden from the app
                    .put("WaitTimeSeconds", 0);
            req.putArray("MessageAttributeNames").add("All");
            req.putArray("MessageSystemAttributeNames").add("All");
            JsonNode got = call(b, "ReceiveMessage", req).path("Messages");
            if (got.isEmpty()) {
                break;
            }
            for (JsonNode m : got) {
                Map<String, String> headers = new LinkedHashMap<>();
                m.path("MessageAttributes")
                        .properties()
                        .forEach(e -> headers.put(
                                e.getKey(), e.getValue().path("StringValue").asString("")));
                String group = m.path("Attributes").path("MessageGroupId").asString("");
                Optional<Instant> at = m.path("Attributes").has("SentTimestamp")
                        ? Optional.of(Instant.ofEpochMilli(
                                m.path("Attributes").path("SentTimestamp").asLong()))
                        : Optional.empty();
                seen.putIfAbsent(
                        m.path("MessageId").asString(),
                        new Message(
                                m.path("MessageId").asString(),
                                at,
                                group.isEmpty() ? Optional.empty() : Optional.of(group),
                                m.path("Body").asString(),
                                headers));
            }
        }
        return new ArrayList<>(seen.values()).subList(0, Math.min(max, seen.size()));
    }

    @Override
    public void publish(Broker b, String queue, String body, Optional<String> key) {
        ObjectNode req = JSON.createObjectNode().put("QueueUrl", url(b, queue)).put("MessageBody", body);
        if (queue.endsWith(".fifo")) {
            // A FIFO queue needs a group (the key) and a de-duplication id: each click is a new message.
            req.put("MessageGroupId", key.orElse("devdesk"));
            req.put("MessageDeduplicationId", Long.toString(clock.millis()));
        }
        call(b, "SendMessage", req);
    }

    @Override
    public List<Reader> readers(Broker b, String queue) {
        // SQS's readers poll and leave: the API has no list of them. Who is connected comes from this machine's
        // socket tables instead (shown next to it).
        return List.of(new Reader("(SQS doesn't keep a list of who reads)", "", "", -1));
    }

    private JsonNode attributes(Broker b, String url) {
        ObjectNode req = JSON.createObjectNode().put("QueueUrl", url);
        req.putArray("AttributeNames").add("ApproximateNumberOfMessages").add("ApproximateNumberOfMessagesNotVisible");
        return call(b, "GetQueueAttributes", req).path("Attributes");
    }

    private String url(Broker b, String queue) {
        return call(b, "GetQueueUrl", JSON.createObjectNode().put("QueueName", queue))
                .path("QueueUrl")
                .asString();
    }

    /** "http://localhost:4566/000000000000/orders" → "orders". */
    private static String name(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    /** One SQS call in its JSON protocol: the operation in a header, its input as the body. */
    private JsonNode call(Broker b, String operation, ObjectNode input) {
        byte[] body = input.toString().getBytes(StandardCharsets.UTF_8);
        String host = b.host() + ":" + b.port();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("content-type", "application/x-amz-json-1.0");
        headers.put("x-amz-target", "AmazonSQS." + operation);
        Map<String, String> signed = new LinkedHashMap<>(headers);
        signed.put("host", host);
        Map<String, String> auth = SigV4.sign(
                "POST",
                "/",
                Map.of(),
                signed,
                SigV4.sha256Hex(body),
                b.user(),
                b.password(),
                REGION,
                "sqs",
                clock.instant());
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://" + host + "/"))
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(req::header);
        auth.forEach(req::header);
        try {
            HttpResponse<String> r = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
            if (r.statusCode() >= 300) {
                String type = json.path("__type").asString("");
                String message =
                        json.path("message").asString(json.path("Message").asString(r.body()));
                // AWS says "NonExistentQueue", newer servers "QueueDoesNotExist": both mean the same.
                String hint = type.contains("NonExistentQueue") || type.contains("QueueDoesNotExist")
                        ? " Check the queue's name, or let the app create it first."
                        : "";
                throw new DataException(message + hint);
            }
            return json;
        } catch (ConnectException e) {
            throw new DataException(
                    "Can't reach " + b.service() + " at " + host + ". Is it running? Start it in" + " Services.", e);
        } catch (IOException | RuntimeException e) {
            if (e instanceof DataException d) {
                throw d;
            }
            throw new DataException(b.service() + " didn't answer like SQS: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataException("interrupted", e);
        }
    }
}
