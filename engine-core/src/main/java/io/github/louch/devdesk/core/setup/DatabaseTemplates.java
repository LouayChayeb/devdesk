package io.github.louch.devdesk.core.setup;

import io.github.louch.devdesk.core.container.ContainerRuntime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.random.RandomGenerator;

/**
 * Ready-made service cards for the wizard's "Add database" button. Each one is a container DevDesk's Data page can open
 * right away (see DatabaseTargets): the image's own variable names, a published port, a generated password.
 */
public final class DatabaseTemplates {

    /** The kinds the button offers, in menu order. */
    public static final List<String> KINDS = List.of(
            "postgres",
            "mysql",
            "redis",
            "mongo",
            "mailpit",
            "rabbitmq",
            "kafka",
            "elasticsearch",
            "s3",
            "sqs",
            "localstack");

    /**
     * What an app sets to use a local cloud stand-in instead of AWS: the SDKs of every language read these. The keys
     * are the stand-ins' fixed test keys, not secrets.
     */
    public static Map<String, String> awsSettings(String endpoint) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("AWS_ENDPOINT_URL", endpoint);
        env.put("AWS_ACCESS_KEY_ID", "test");
        env.put("AWS_SECRET_ACCESS_KEY", "test");
        env.put("AWS_REGION", "us-east-1");
        return env;
    }

    private static final String PASSWORD_CHARS = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private DatabaseTemplates() {}

    /**
     * A new card for {@code kind}.
     *
     * @param takenNames service names the draft already uses
     * @param takenPorts host ports the draft already uses
     * @param portFree whether nothing on this machine listens on a port yet
     */
    public static Optional<SetupDraft.Service> create(
            String kind,
            String project,
            Set<String> takenNames,
            Set<Integer> takenPorts,
            IntPredicate portFree,
            RandomGenerator random) {
        String name = uniqueName(kind, takenNames);
        String container = ContainerRuntime.containerName(project, name);
        IntPredicate usable = p -> !takenPorts.contains(p) && portFree.test(p);
        String volume = (project + "-" + name + "-data").replaceAll("[^a-zA-Z0-9_.-]", "_");
        Map<String, String> env = new LinkedHashMap<>();
        return Optional.ofNullable(
                switch (kind) {
                    case "postgres" -> {
                        int port = firstFree(5432, usable);
                        env.put("POSTGRES_PASSWORD", password(random));
                        env.put("POSTGRES_DB", databaseName(project));
                        yield card(
                                name,
                                "postgres:18",
                                port + ":5432",
                                volume + ":/var/lib/postgresql",
                                env,
                                // Asks Postgres itself: Docker's port forwarder accepts connections before Postgres is
                                // ready.
                                SetupDraft.Health.command(
                                        "docker exec " + container + " pg_isready -h 127.0.0.1 -U postgres", "40s"));
                    }
                    case "mysql" -> {
                        int port = firstFree(3306, usable);
                        env.put("MYSQL_ROOT_PASSWORD", password(random));
                        env.put("MYSQL_DATABASE", databaseName(project));
                        yield card(
                                name,
                                "mysql:9",
                                port + ":3306",
                                volume + ":/var/lib/mysql",
                                env,
                                // The password is read inside the container, so it never appears in the command list.
                                SetupDraft.Health.command(
                                        "docker exec " + container
                                                + " sh -c 'mysqladmin ping -h 127.0.0.1 -uroot -p\"$MYSQL_ROOT_PASSWORD\" --silent'",
                                        "60s"));
                    }
                    case "redis" -> {
                        int port = firstFree(6379, usable);
                        yield card(
                                name,
                                "redis:8-alpine",
                                port + ":6379",
                                volume + ":/data",
                                env,
                                SetupDraft.Health.command("docker exec " + container + " redis-cli ping", "20s"));
                    }
                    case "mongo" -> {
                        int port = firstFree(27017, usable);
                        env.put("MONGO_INITDB_ROOT_USERNAME", "root");
                        env.put("MONGO_INITDB_ROOT_PASSWORD", password(random));
                        yield card(
                                name,
                                "mongo:8",
                                port + ":27017",
                                volume + ":/data/db",
                                env,
                                SetupDraft.Health.command(
                                        "docker exec " + container
                                                + " mongosh --quiet --eval \"db.adminCommand('ping')\"",
                                        "40s"));
                    }
                    case "mailpit" -> {
                        int web = firstFree(8025, usable);
                        int smtp = firstFree(1025, p -> p != web && usable.test(p));
                        // Skips a reverse-DNS lookup per mail that takes 10 s in Docker on WSL.
                        env.put("MP_SMTP_DISABLE_RDNS", "true");
                        SetupDraft.Service c = card(
                                name,
                                "axllent/mailpit:latest",
                                web + ":8025",
                                "",
                                env,
                                new SetupDraft.Health("http", "http://127.0.0.1:" + web + "/readyz", "20s"));
                        yield new SetupDraft.Service(
                                c.name(),
                                c.kind(),
                                c.command(),
                                c.directory(),
                                c.image(),
                                List.of(web + ":8025", smtp + ":1025"),
                                List.of(),
                                c.environment(),
                                c.dependsOn(),
                                c.health(),
                                c.restart(),
                                c.origin(),
                                c.notes());
                    }
                    case "rabbitmq" -> {
                        int amqp = firstFree(5672, usable);
                        int web = firstFree(15672, p -> p != amqp && usable.test(p));
                        // The "-management" image adds the web API DevDesk's Inbox reads (and RabbitMQ's own UI).
                        yield withPorts(
                                card(
                                        name,
                                        "rabbitmq:4-management",
                                        amqp + ":5672",
                                        volume + ":/var/lib/rabbitmq",
                                        env,
                                        SetupDraft.Health.command(
                                                // As RabbitMQ's own user: run as root, the first check would create
                                                // ~/.erlang.cookie owned by root, and RabbitMQ couldn't start (eacces).
                                                "docker exec -u rabbitmq " + container
                                                        + " rabbitmq-diagnostics -q ping",
                                                "60s")),
                                List.of(amqp + ":5672", web + ":15672"),
                                List.of("Apps connect to amqp://guest:guest@localhost:" + amqp
                                        + "; its web UI is http://localhost:" + web));
                    }
                    case "kafka" -> {
                        int port = firstFree(9092, usable);
                        // One node that is its own controller (KRaft, no ZooKeeper). It must tell clients an address
                        // this machine can reach: localhost and the published port.
                        env.put("KAFKA_NODE_ID", "1");
                        env.put("KAFKA_PROCESS_ROLES", "broker,controller");
                        env.put("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093");
                        env.put("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + port);
                        env.put("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER");
                        env.put("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT");
                        env.put("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093");
                        env.put("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1");
                        env.put("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1");
                        env.put("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1");
                        yield withPorts(
                                card(
                                        name,
                                        "apache/kafka:4.1.0",
                                        port + ":9092",
                                        "",
                                        env,
                                        new SetupDraft.Health("tcp", Integer.toString(port), "60s")),
                                List.of(port + ":9092"),
                                List.of("Apps connect to localhost:" + port));
                    }
                    case "elasticsearch" -> {
                        int port = firstFree(9200, usable);
                        // Security off: plain HTTP without a password, for this machine only.
                        env.put("discovery.type", "single-node");
                        env.put("xpack.security.enabled", "false");
                        env.put("ES_JAVA_OPTS", "-Xms512m -Xmx512m");
                        yield card(
                                name,
                                "docker.elastic.co/elasticsearch/elasticsearch:8.19.4",
                                port + ":9200",
                                volume + ":/usr/share/elasticsearch/data",
                                env,
                                new SetupDraft.Health("http", "http://127.0.0.1:" + port + "/_cluster/health", "90s"));
                    }
                    case "s3" -> {
                        // RustFS: an S3 server under a free licence that reads MinIO's settings too.
                        int port = firstFree(9000, usable);
                        env.put("RUSTFS_ACCESS_KEY", "test");
                        env.put("RUSTFS_SECRET_KEY", password(random));
                        Map<String, String> app = awsSettings("http://localhost:" + port);
                        app.put("AWS_SECRET_ACCESS_KEY", env.get("RUSTFS_SECRET_KEY"));
                        yield withPorts(
                                card(
                                        name,
                                        "rustfs/rustfs:latest",
                                        port + ":9000",
                                        volume + ":/data",
                                        env,
                                        new SetupDraft.Health("tcp", Integer.toString(port), "30s")),
                                List.of(port + ":9000"),
                                List.of("A local S3: point the app's S3 client at it (path-style addresses): "
                                        + settings(app)));
                    }
                    case "sqs" -> {
                        // ElasticMQ: Amazon SQS's API, in memory, in one small container.
                        int port = firstFree(9324, usable);
                        yield withPorts(
                                card(
                                        name,
                                        "softwaremill/elasticmq-native:latest",
                                        port + ":9324",
                                        "",
                                        env,
                                        new SetupDraft.Health("tcp", Integer.toString(port), "30s")),
                                List.of(port + ":9324"),
                                List.of("A local SQS: point the app's SQS client at it: "
                                        + settings(awsSettings("http://localhost:" + port))));
                    }
                    case "localstack" -> {
                        // LocalStack's free edition: S3, SQS, SNS, DynamoDB, Lambda... behind one port.
                        int port = firstFree(4566, usable);
                        env.put("SERVICES", "s3,sqs,sns,dynamodb");
                        yield withPorts(
                                card(
                                        name,
                                        // Pinned: newer LocalStack images quit without a paid account's token.
                                        "localstack/localstack:3.8.1",
                                        port + ":4566",
                                        "",
                                        env,
                                        new SetupDraft.Health(
                                                "http", "http://127.0.0.1:" + port + "/_localstack/health", "60s")),
                                List.of(port + ":4566"),
                                List.of("An offline AWS (S3, SQS, SNS, DynamoDB): point the app's AWS SDK at it: "
                                        + settings(awsSettings("http://localhost:" + port))));
                    }
                    default -> null;
                });
    }

    /** "A=1 B=2": settings as one line to copy into an app's environment. */
    private static String settings(Map<String, String> env) {
        StringBuilder sb = new StringBuilder();
        env.forEach((k, v) ->
                sb.append(sb.isEmpty() ? "" : " ").append(k).append('=').append(v));
        return sb.toString();
    }

    /** The card with these published ports and notes (a broker has a client port and a web port). */
    private static SetupDraft.Service withPorts(SetupDraft.Service c, List<String> ports, List<String> notes) {
        return new SetupDraft.Service(
                c.name(),
                c.kind(),
                c.command(),
                c.directory(),
                c.image(),
                ports,
                c.volumes(),
                c.environment(),
                c.dependsOn(),
                c.health(),
                c.restart(),
                c.origin(),
                notes);
    }

    private static SetupDraft.Service card(
            String name, String image, String port, String volume, Map<String, String> env, SetupDraft.Health health) {
        return new SetupDraft.Service(
                name,
                "container",
                "",
                "",
                image,
                List.of(port),
                volume.isEmpty() ? List.of() : List.of(volume),
                env,
                Map.of(),
                health,
                "no",
                "added by you",
                List.of());
    }

    /** "postgres", or "postgres-2" when the draft already has one. */
    static String uniqueName(String base, Set<String> taken) {
        String name = base;
        for (int i = 2; taken.contains(name); i++) {
            name = base + "-" + i;
        }
        return name;
    }

    /** The usual port when it's free (tools and READMEs expect it), else the next free one above it. */
    static int firstFree(int preferred, IntPredicate usable) {
        for (int p = preferred; p < preferred + 200 && p <= 65_535; p++) {
            if (usable.test(p)) {
                return p;
            }
        }
        return preferred; // everything near it is taken: the test run's diagnosis will say by whom
    }

    /** "my-shop" → "my_shop": database names can't contain "-" without quoting. */
    static String databaseName(String project) {
        String n = project.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        return n.isEmpty() || Character.isDigit(n.charAt(0)) ? "app" : n;
    }

    /** 20 letters and digits, without look-alikes (0/O, 1/l): it's a local dev password people may type. */
    static String password(RandomGenerator random) {
        StringBuilder sb = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }
}
