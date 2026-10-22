package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.queue.Broker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Against a real local SQS (ElasticMQ). */
@Tag("docker")
class SqsQueuesTest {

    private static TestContainer server;
    private static Broker broker;
    private final SqsQueues sqs = new SqsQueues(Clock.systemUTC());

    @BeforeAll
    static void start() throws Exception {
        server = TestContainer.start("sqs", "softwaremill/elasticmq-native:latest", 9324, port -> {
            try {
                // Its query API: creating the queue is also the readiness check.
                var r = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .build()
                        .send(
                                HttpRequest.newBuilder(URI.create(
                                                "http://127.0.0.1:" + port + "/?Action=CreateQueue&QueueName=orders"))
                                        .build(),
                                HttpResponse.BodyHandlers.discarding());
                return r.statusCode() == 200;
            } catch (Exception e) {
                return false;
            }
        });
        broker = new Broker("sqs", Broker.Kind.SQS, "127.0.0.1", server.port, server.port, "test", "test", "");
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void sendPeekAndTheMessagesStayForTheApp() {
        sqs.publish(broker, "orders", "{\"order\": 1}", Optional.empty());
        sqs.publish(broker, "orders", "{\"order\": 2}", Optional.empty());
        var seen = sqs.peek(broker, "orders", 10);
        assertThat(seen).extracting(m -> m.body()).containsExactlyInAnyOrder("{\"order\": 1}", "{\"order\": 2}");
        assertThat(seen.getFirst().at()).isPresent();
        // Looked at with a zero visibility timeout: still there for the app.
        assertThat(sqs.peek(broker, "orders", 10)).hasSize(2);
        assertThat(sqs.queues(broker)).anySatisfy(q -> {
            assertThat(q.name()).isEqualTo("orders");
            assertThat(q.waiting()).isEqualTo(2);
        });
        assertThat(sqs.readers(broker, "orders"))
                .singleElement()
                .satisfies(r -> assertThat(r.behind()).isEqualTo(-1));
    }

    @Test
    void aMissingQueueIsExplained() {
        assertThatThrownBy(() -> sqs.peek(broker, "nothing-here", 5))
                .hasMessageContaining("does not exist")
                .hasMessageContaining("Check the queue's name");
    }
}
