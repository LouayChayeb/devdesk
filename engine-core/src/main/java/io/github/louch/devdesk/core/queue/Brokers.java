package io.github.louch.devdesk.core.queue;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the project's brokers with no configuration, like the databases: a RabbitMQ container with its management
 * API published (the "-management" images), a Kafka container with its client port published, or a local SQS
 * (ElasticMQ, LocalStack).
 */
public final class Brokers {

    static final int RABBIT_API = 15672;
    static final int RABBIT_AMQP = 5672;
    static final int KAFKA_PORT = 9092;
    static final int ELASTICMQ_PORT = 9324;
    static final int LOCALSTACK_PORT = 4566;

    private Brokers() {}

    public static List<Broker> find(ProjectSpec project) {
        List<Broker> out = new ArrayList<>();
        for (ServiceSpec s : project.services().values()) {
            broker(project, s).ifPresent(out::add);
        }
        return out;
    }

    public static Optional<Broker> find(ProjectSpec project, String service) {
        return Optional.ofNullable(project.services().get(service)).flatMap(s -> broker(project, s));
    }

    private static Optional<Broker> broker(ProjectSpec project, ServiceSpec s) {
        if (!(s.workload() instanceof Workload.Container c)) {
            return Optional.empty();
        }
        Map<String, String> env = new LinkedHashMap<>(project.environment());
        env.putAll(s.environment());
        String repo = repository(c.image());
        if (repo.equals("rabbitmq")) {
            int amqp = hostPort(c, RABBIT_AMQP);
            // The image's defaults are guest/guest, which RabbitMQ accepts from the same machine only: fine here.
            return c.ports().stream()
                    .filter(p -> p.containerPort() == RABBIT_API)
                    .findFirst()
                    .map(p -> new Broker(
                            s.name(),
                            Broker.Kind.RABBITMQ,
                            host(p.hostAddress()),
                            p.hostPort(),
                            amqp,
                            env.getOrDefault("RABBITMQ_DEFAULT_USER", "guest"),
                            env.getOrDefault("RABBITMQ_DEFAULT_PASS", "guest"),
                            env.getOrDefault("RABBITMQ_DEFAULT_VHOST", "/")));
        }
        if (repo.equals("kafka") || repo.equals("kafka-native") || repo.equals("cp-kafka") || repo.equals("redpanda")) {
            return c.ports().stream()
                    .filter(p -> p.containerPort() == KAFKA_PORT)
                    .findFirst()
                    .map(p -> new Broker(
                            s.name(),
                            Broker.Kind.KAFKA,
                            host(p.hostAddress()),
                            p.hostPort(),
                            p.hostPort(),
                            "",
                            "",
                            ""));
        }
        if (repo.startsWith("elasticmq") || repo.equals("localstack")) {
            int inside = repo.equals("localstack") ? LOCALSTACK_PORT : ELASTICMQ_PORT;
            // A local SQS takes any keys; "test" is what LocalStack's guides use.
            return c.ports().stream()
                    .filter(p -> p.containerPort() == inside)
                    .findFirst()
                    .map(p -> new Broker(
                            s.name(),
                            Broker.Kind.SQS,
                            host(p.hostAddress()),
                            p.hostPort(),
                            p.hostPort(),
                            "test",
                            "test",
                            ""));
        }
        return Optional.empty();
    }

    private static int hostPort(Workload.Container c, int containerPort) {
        return c.ports().stream()
                .filter(p -> p.containerPort() == containerPort)
                .mapToInt(Workload.PortMapping::hostPort)
                .findFirst()
                .orElse(-1);
    }

    private static String host(String address) {
        return address.isEmpty() || address.equals("0.0.0.0") ? "127.0.0.1" : address;
    }

    private static String repository(String image) {
        String name = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        int slash = name.lastIndexOf('/');
        int colon = name.lastIndexOf(':');
        return name.substring(slash + 1, colon > slash ? colon : name.length());
    }
}
