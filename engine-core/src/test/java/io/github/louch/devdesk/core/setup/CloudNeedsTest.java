package io.github.louch.devdesk.core.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CloudNeedsTest {

    private static CloudNeeds.Result scan(Map<String, String> files) {
        return CloudNeeds.scan(List.of("", "api", "worker"), f -> Optional.ofNullable(files.get(f)));
    }

    @Test
    void oneServiceGetsItsOwnStandIn() {
        var s3 = scan(Map.of("api/package.json", "{\"dependencies\": {\"@aws-sdk/client-s3\": \"^3\"}}"));
        assertThat(s3.services()).containsExactly("s3");
        assertThat(s3.foundIn()).containsExactly("api/package.json");
        assertThat(s3.suggestion()).contains("s3");
        assertThat(scan(Map.of("worker/pom.xml", "<artifactId>sqs</artifactId>"))
                        .suggestion())
                .contains("sqs");
    }

    @Test
    void severalOrAllOfAwsGetLocalStack() {
        var both = scan(Map.of(
                "api/package.json", "\"@aws-sdk/client-s3\" \"@aws-sdk/client-sqs\"",
                "worker/requirements.txt", "boto3==1.34\n"));
        assertThat(both.services()).contains("s3", "sqs", "aws");
        assertThat(both.suggestion()).contains("localstack");
        assertThat(scan(Map.of("api/package.json", "{\"dependencies\": {\"express\": \"4\"}}"))
                        .suggestion())
                .isEmpty();
    }

    @Test
    void theNewTemplatesComeWithTheAppsSettings() {
        var card = DatabaseTemplates.create(
                        "localstack",
                        "shop",
                        java.util.Set.of(),
                        java.util.Set.of(),
                        p -> true,
                        new java.util.Random(1))
                .orElseThrow();
        assertThat(card.image()).startsWith("localstack/localstack");
        assertThat(card.ports()).containsExactly("4566:4566");
        assertThat(card.notes().getFirst())
                .contains("AWS_ENDPOINT_URL=http://localhost:4566", "AWS_ACCESS_KEY_ID=test");
        var kafka = DatabaseTemplates.create(
                        "kafka", "shop", java.util.Set.of(), java.util.Set.of(9092), p -> true, new java.util.Random(1))
                .orElseThrow();
        assertThat(kafka.environment()).containsEntry("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:9093");
        var rabbit = DatabaseTemplates.create(
                        "rabbitmq", "shop", java.util.Set.of(), java.util.Set.of(), p -> true, new java.util.Random(1))
                .orElseThrow();
        assertThat(rabbit.volumes()).containsExactly("shop-rabbitmq-data:/var/lib/rabbitmq");
        assertThat(rabbit.health().target()).contains("docker exec -u rabbitmq");
        for (String k : DatabaseTemplates.KINDS) {
            assertThat(DatabaseTemplates.create(
                            k, "shop", java.util.Set.of(), java.util.Set.of(), p -> true, new java.util.Random(1)))
                    .as(k)
                    .isPresent();
        }
    }
}
