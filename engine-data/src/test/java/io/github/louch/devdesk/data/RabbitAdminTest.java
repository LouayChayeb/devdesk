package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.queue.Broker;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Against a real RabbitMQ with its management API. */
@Tag("docker")
class RabbitAdminTest {

    private static TestContainer server;
    private static Broker broker;
    private final RabbitAdmin rabbit = new RabbitAdmin();

    @BeforeAll
    static void start() throws Exception {
        server = TestContainer.start(
                "rabbit", "rabbitmq:4-management", 15672, port -> put(port, "/api/queues/%2F/orders") < 300);
        broker = new Broker("queue", Broker.Kind.RABBITMQ, "127.0.0.1", server.port, -1, "guest", "guest", "/");
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    private static int put(int port, String path) {
        try {
            var r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .header("Authorization", "Basic " + Base64.getEncoder().encodeToString("guest:guest".getBytes()))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString("{\"durable\":true}"))
                    .build();
            return HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(r, HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (Exception e) {
            return 999;
        }
    }

    @Test
    void publishPeekAndTheMessagesStay() throws InterruptedException {
        rabbit.publish(broker, "orders", "{\"order\": 42}", Optional.of("o-42"));
        rabbit.publish(broker, "orders", "second", Optional.empty());

        var first = rabbit.peek(broker, "orders", 10);
        assertThat(first).extracting(m -> m.body()).containsExactly("{\"order\": 42}", "second");
        assertThat(first.getFirst().headers()).containsEntry("key", "o-42");
        assertThat(first.getFirst().at()).isPresent();

        // Looking didn't take them: they are all still waiting.
        assertThat(rabbit.peek(broker, "orders", 10)).hasSize(2);
        // RabbitMQ refreshes the counts its management API shows every few seconds.
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (rabbit.queues(broker).stream().noneMatch(q -> q.waiting() == 2) && System.nanoTime() < deadline) {
            Thread.sleep(500);
        }
        assertThat(rabbit.queues(broker)).anySatisfy(q -> {
            assertThat(q.name()).isEqualTo("orders");
            assertThat(q.waiting()).isEqualTo(2);
            assertThat(q.readers()).isZero();
        });
        assertThat(rabbit.readers(broker, "orders")).isEmpty();
    }

    @Test
    void mistakesAreExplained() {
        assertThatThrownBy(() -> rabbit.publish(broker, "nobody-listens", "x", Optional.empty()))
                .hasMessageContaining("no queue called nobody-listens");
        var wrong = new Broker("queue", Broker.Kind.RABBITMQ, "127.0.0.1", server.port, -1, "guest", "nope", "/");
        assertThatThrownBy(() -> rabbit.queues(wrong)).hasMessageContaining("RABBITMQ_DEFAULT_USER");
    }
}
