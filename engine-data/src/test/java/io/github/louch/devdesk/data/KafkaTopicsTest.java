package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.queue.Broker;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Against a real single-node Kafka (KRaft, no ZooKeeper). */
@Tag("docker")
class KafkaTopicsTest {

    private static TestContainer server;
    private static Broker broker;
    private final KafkaTopics kafka = new KafkaTopics();

    @BeforeAll
    static void start() throws Exception {
        int port = TestContainer.freePort();
        broker = new Broker("events", Broker.Kind.KAFKA, "127.0.0.1", port, port, "", "", "");
        server = TestContainer.start(
                "kafka",
                "apache/kafka:4.1.0",
                9092,
                port,
                p -> {
                    try {
                        new KafkaTopics().queues(broker);
                        return true;
                    } catch (RuntimeException e) {
                        return false;
                    }
                },
                "KAFKA_NODE_ID=1",
                "KAFKA_PROCESS_ROLES=broker,controller",
                "KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093",
                "KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:" + port,
                "KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER",
                "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
                "KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093",
                "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1",
                "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1",
                "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1",
                "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0");
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void topicsMessagesAndWhoReadsThem() {
        kafka.publish(broker, "orders", "{\"order\": 1}", Optional.of("o-1"));
        kafka.publish(broker, "orders", "{\"order\": 2}", Optional.empty());
        kafka.publish(broker, "orders", "{\"order\": 3}", Optional.of("o-3"));

        var newest = kafka.peek(broker, "orders", 2);
        assertThat(newest).extracting(m -> m.body()).containsExactly("{\"order\": 3}", "{\"order\": 2}");
        assertThat(newest.getFirst().key()).contains("o-3");
        assertThat(newest.getFirst().position()).isEqualTo("0/2");

        // An app's consumer group reads two of the three and commits.
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + broker.port());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "billing");
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, "billing-worker");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        try (var app = new KafkaConsumer<String, String>(p)) {
            app.subscribe(List.of("orders"));
            int read = 0;
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (read < 2 && System.nanoTime() < deadline) {
                read += app.poll(Duration.ofMillis(500)).count();
            }
            app.commitSync();

            var readers = kafka.readers(broker, "orders");
            assertThat(readers).singleElement().satisfies(r -> {
                assertThat(r.name()).isEqualTo("billing");
                assertThat(r.client()).isEqualTo("billing-worker");
                assertThat(r.behind()).isEqualTo(1);
            });
        }
        assertThat(kafka.queues(broker)).anySatisfy(q -> {
            assertThat(q.name()).isEqualTo("orders");
            assertThat(q.waiting()).isEqualTo(3);
            assertThat(q.inFlight()).isEqualTo(1);
            assertThat(q.readers()).isEqualTo(1);
        });
    }

    @Test
    void aMissingTopicIsExplained() {
        assertThatThrownBy(() -> kafka.peek(broker, "nothing-here", 5)).hasMessageContaining("nothing-here");
    }
}
