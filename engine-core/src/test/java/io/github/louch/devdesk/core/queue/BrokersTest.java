package io.github.louch.devdesk.core.queue;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.DatabaseTargets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BrokersTest {

    @Test
    void brokersAndLocalCloudAreFoundFromTheirImages() throws ConfigException {
        var p = ConfigParser.parse("""
                version: 1
                name: shop
                services:
                  rabbit:
                    container:
                      image: rabbitmq:4-management
                      ports: ["5672:5672", "15672:15672"]
                      environment: { RABBITMQ_DEFAULT_USER: shop, RABBITMQ_DEFAULT_PASS: pw }
                  events:
                    container: { image: apache/kafka:4.1.0, ports: ["9092:9092"] }
                  sqs:
                    container: { image: softwaremill/elasticmq-native, ports: ["9324:9324"] }
                  aws:
                    container: { image: localstack/localstack, ports: ["4566:4566"] }
                  plain-rabbit:
                    container: { image: rabbitmq:4, ports: ["5673:5672"] }
                """, Path.of("/work/shop"), Map.of(), f -> Optional.empty());
        var brokers = Brokers.find(p);
        assertThat(brokers).extracting(Broker::service).containsExactly("rabbit", "events", "sqs", "aws");
        Broker rabbit = brokers.getFirst();
        assertThat(rabbit.port()).isEqualTo(15672);
        assertThat(rabbit.clientPort()).isEqualTo(5672);
        assertThat(rabbit.user()).isEqualTo("shop");
        assertThat(brokers.get(2).kind()).isEqualTo(Broker.Kind.SQS);
        assertThat(brokers.get(3).port()).isEqualTo(4566);
        // LocalStack is also an S3 for the Data page.
        assertThat(DatabaseTargets.find(p)).anySatisfy(t -> {
            assertThat(t.service()).isEqualTo("aws");
            assertThat(t.kind()).isEqualTo(DatabaseTarget.Kind.OBJECTS);
            assertThat(t.user()).isEqualTo("test");
        });
    }
}
