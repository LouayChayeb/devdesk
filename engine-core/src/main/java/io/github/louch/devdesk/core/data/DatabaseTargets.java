package io.github.louch.devdesk.core.data;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Finds the project's databases with no configuration: a container whose image is Postgres, MySQL/MariaDB or Redis,
 * published on a host port, with the user, password and database name from the same variables the images read.
 * SQLite files are found on disk by the daemon (they aren't services).
 */
public final class DatabaseTargets {

    /** Postgres listens on 5432 inside its container; the official image and most derived ones keep it. */
    static final int POSTGRES_PORT = 5432;
    /** Redis's default port inside its container. */
    static final int REDIS_PORT = 6379;
    /** MongoDB's port inside its container. */
    static final int MONGO_PORT = 27017;
    /** MySQL's and MariaDB's port inside their containers. */
    static final int MYSQL_PORT = 3306;
    /** MinIO's and RustFS's S3 port inside their containers (the web console is 9001). */
    static final int S3_PORT = 9000;
    /** Elasticsearch's and OpenSearch's HTTP port inside their containers. */
    static final int SEARCH_PORT = 9200;
    /** LocalStack's single port for every AWS service it stands in for. */
    static final int LOCALSTACK_PORT = 4566;

    private DatabaseTargets() {}

    public static List<DatabaseTarget> find(ProjectSpec project) {
        List<DatabaseTarget> found = new ArrayList<>();
        for (ServiceSpec s : project.services().values()) {
            target(withEnvFiles(project, s)).ifPresent(found::add);
        }
        return found;
    }

    public static Optional<DatabaseTarget> find(ProjectSpec project, String service) {
        return Optional.ofNullable(project.services().get(service)).flatMap(s -> target(withEnvFiles(project, s)));
    }

    /**
     * The service as its container sees it: the env_files' variables first, its own on top (ServiceEnvironment's
     * order). A POSTGRES_PASSWORD kept in .env must open the database just like one written in devdesk.yaml.
     */
    private static ServiceSpec withEnvFiles(ProjectSpec project, ServiceSpec s) {
        Map<String, String> env = new LinkedHashMap<>(project.environment());
        env.putAll(s.environment());
        return new ServiceSpec(
                s.name(),
                s.workload(),
                s.directory(),
                env,
                s.dependsOn(),
                s.ports(),
                s.health(),
                s.restart(),
                s.stopGracePeriod(),
                s.watch(),
                s.test(),
                s.perBranch(),
                s.sleepAfter());
    }

    /** Whether the Workbench can open this service as a database. */
    public static boolean isDatabase(ServiceSpec s) {
        return target(s).isPresent();
    }

    private static Optional<DatabaseTarget> target(ServiceSpec s) {
        return postgres(s)
                .or(() -> mysql(s))
                .or(() -> redis(s))
                .or(() -> mongo(s))
                .or(() -> objects(s))
                .or(() -> search(s));
    }

    private static Optional<DatabaseTarget> redis(ServiceSpec s) {
        if (!(s.workload() instanceof Workload.Container c) || !isRedisImage(c.image())) {
            return Optional.empty();
        }
        return c.ports().stream()
                .filter(p -> p.containerPort() == REDIS_PORT)
                .findFirst()
                .map(p -> {
                    Map<String, String> env = s.environment();
                    // The official image takes its password as "--requirepass" in the command; Bitnami's image and
                    // many compose files use REDIS_PASSWORD instead. DevDesk reads both.
                    String password = c.command()
                            .flatMap(DatabaseTargets::requirepass)
                            .orElse(env.getOrDefault("REDIS_PASSWORD", ""));
                    return new DatabaseTarget(
                            s.name(),
                            DatabaseTarget.Kind.REDIS,
                            host(p.hostAddress()),
                            p.hostPort(),
                            env.getOrDefault("REDIS_USERNAME", ""),
                            password,
                            "0");
                });
    }

    private static Optional<DatabaseTarget> mongo(ServiceSpec s) {
        if (!(s.workload() instanceof Workload.Container c) || !isMongoImage(c.image())) {
            return Optional.empty();
        }
        return c.ports().stream()
                .filter(p -> p.containerPort() == MONGO_PORT)
                .findFirst()
                .map(p -> {
                    // The official image creates this root user on first start; Bitnami's reads its own names.
                    Map<String, String> env = s.environment();
                    return new DatabaseTarget(
                            s.name(),
                            DatabaseTarget.Kind.MONGO,
                            host(p.hostAddress()),
                            p.hostPort(),
                            first(env, "MONGO_INITDB_ROOT_USERNAME", "MONGODB_ROOT_USER"),
                            first(env, "MONGO_INITDB_ROOT_PASSWORD", "MONGODB_ROOT_PASSWORD"),
                            first(env, "MONGO_INITDB_DATABASE", "MONGODB_DATABASE"));
                });
    }

    private static Optional<DatabaseTarget> objects(ServiceSpec s) {
        if (!(s.workload() instanceof Workload.Container c) || !isObjectStoreImage(c.image())) {
            return Optional.empty();
        }
        boolean rustfs = repository(c.image()).contains("rustfs");
        // LocalStack answers S3 (and the rest of its AWS) on one port, with any keys ("test" by convention).
        boolean localstack = repository(c.image()).equals("localstack");
        int inside = localstack ? LOCALSTACK_PORT : S3_PORT;
        return c.ports().stream()
                .filter(p -> p.containerPort() == inside)
                .findFirst()
                .map(p -> {
                    // The keys the image starts with; both accept MinIO's names. Without any, each has its own
                    // well-known default (fine for a laptop, which is why the images warn about it).
                    Map<String, String> env = s.environment();
                    String user =
                            first(env, "MINIO_ROOT_USER", "RUSTFS_ACCESS_KEY", "RUSTFS_ROOT_USER", "MINIO_ACCESS_KEY");
                    String password = first(
                            env,
                            "MINIO_ROOT_PASSWORD",
                            "RUSTFS_SECRET_KEY",
                            "RUSTFS_ROOT_PASSWORD",
                            "MINIO_SECRET_KEY");
                    String fallback = localstack ? "test" : rustfs ? "rustfsadmin" : "minioadmin";
                    return new DatabaseTarget(
                            s.name(),
                            DatabaseTarget.Kind.OBJECTS,
                            host(p.hostAddress()),
                            p.hostPort(),
                            user.isEmpty() ? fallback : user,
                            password.isEmpty() ? fallback : password,
                            "");
                });
    }

    /** "minio/minio", "quay.io/minio/minio", "bitnami/minio", "rustfs/rustfs", "localstack/localstack". */
    public static boolean isObjectStoreImage(String image) {
        String repo = repository(image);
        return repo.equals("minio") || repo.equals("rustfs") || repo.equals("localstack");
    }

    private static Optional<DatabaseTarget> search(ServiceSpec s) {
        if (!(s.workload() instanceof Workload.Container c) || !isSearchImage(c.image())) {
            return Optional.empty();
        }
        boolean opensearch = repository(c.image()).contains("opensearch");
        return c.ports().stream()
                .filter(p -> p.containerPort() == SEARCH_PORT)
                .findFirst()
                .map(p -> {
                    // Elasticsearch's built-in user is "elastic", OpenSearch's "admin"; no password = security off.
                    Map<String, String> env = s.environment();
                    String password = opensearch
                            ? first(env, "OPENSEARCH_INITIAL_ADMIN_PASSWORD")
                            : first(env, "ELASTIC_PASSWORD");
                    return new DatabaseTarget(
                            s.name(),
                            DatabaseTarget.Kind.SEARCH,
                            host(p.hostAddress()),
                            p.hostPort(),
                            password.isEmpty() ? "" : opensearch ? "admin" : "elastic",
                            password,
                            "");
                });
    }

    /** "elasticsearch:8", "docker.elastic.co/elasticsearch/elasticsearch", "opensearchproject/opensearch". */
    public static boolean isSearchImage(String image) {
        String repo = repository(image);
        return repo.equals("elasticsearch") || repo.equals("opensearch");
    }

    /** "mongo:8", "mongodb/mongodb-community-server", "bitnami/mongodb". */
    public static boolean isMongoImage(String image) {
        String repo = repository(image);
        return repo.equals("mongo") || repo.equals("mongodb") || repo.startsWith("mongodb-community");
    }

    private static Optional<DatabaseTarget> postgres(ServiceSpec s) {
        if (!(s.workload() instanceof Workload.Container c) || !isPostgresImage(c.image())) {
            return Optional.empty();
        }
        return c.ports().stream()
                .filter(p -> p.containerPort() == POSTGRES_PORT)
                .findFirst()
                .map(p -> {
                    Map<String, String> env = s.environment();
                    // The official image's rules: user defaults to "postgres", the database to the user's name.
                    String user = env.getOrDefault("POSTGRES_USER", "postgres");
                    return new DatabaseTarget(
                            s.name(),
                            DatabaseTarget.Kind.POSTGRES,
                            host(p.hostAddress()),
                            p.hostPort(),
                            user,
                            env.getOrDefault("POSTGRES_PASSWORD", ""),
                            env.getOrDefault("POSTGRES_DB", user));
                });
    }

    private static Optional<DatabaseTarget> mysql(ServiceSpec s) {
        if (!(s.workload() instanceof Workload.Container c) || !isMysqlImage(c.image())) {
            return Optional.empty();
        }
        return c.ports().stream()
                .filter(p -> p.containerPort() == MYSQL_PORT)
                .findFirst()
                .map(p -> {
                    Map<String, String> env = s.environment();
                    // MariaDB's image reads MARIADB_* and still accepts the MYSQL_* names; MySQL's reads MYSQL_*.
                    String rootPassword = first(env, "MARIADB_ROOT_PASSWORD", "MYSQL_ROOT_PASSWORD");
                    boolean emptyRoot = !first(env, "MARIADB_ALLOW_EMPTY_ROOT_PASSWORD", "MYSQL_ALLOW_EMPTY_PASSWORD")
                            .isEmpty();
                    String user = first(env, "MARIADB_USER", "MYSQL_USER");
                    // root sees every session and may stop them; the app user only when root's password is unknown
                    // (MYSQL_RANDOM_ROOT_PASSWORD) or not set.
                    boolean asRoot = !rootPassword.isEmpty() || emptyRoot || user.isEmpty();
                    return new DatabaseTarget(
                            s.name(),
                            DatabaseTarget.Kind.MYSQL,
                            host(p.hostAddress()),
                            p.hostPort(),
                            asRoot ? "root" : user,
                            asRoot ? rootPassword : first(env, "MARIADB_PASSWORD", "MYSQL_PASSWORD"),
                            first(env, "MARIADB_DATABASE", "MYSQL_DATABASE"));
                });
    }

    /** The first of these variables that is set, or "". */
    private static String first(Map<String, String> env, String... names) {
        for (String n : names) {
            String v = env.get(n);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /** "mysql:8.4", "mariadb:11", "percona/percona-server", "bitnami/mysql", "bitnami/mariadb". */
    public static boolean isMysqlImage(String image) {
        String repo = repository(image);
        return repo.equals("mysql")
                || repo.equals("mariadb")
                || repo.startsWith("percona")
                || repo.equals("mysql-server");
    }

    /** "redis-server --requirepass s3cret --appendonly yes" → "s3cret". */
    static Optional<String> requirepass(String command) {
        String[] words = command.trim().split("\\s+");
        for (int i = 0; i < words.length - 1; i++) {
            if (words[i].equals("--requirepass")) {
                return Optional.of(words[i + 1].replaceAll("^[\"']|[\"']$", ""));
            }
        }
        return Optional.empty();
    }

    /** A port published on every address is reached on 127.0.0.1. */
    private static String host(String published) {
        return published.equals("0.0.0.0") ? "127.0.0.1" : published;
    }

    /** "redis:8", "valkey/valkey:8", "redis/redis-stack-server", "bitnami/redis", "eqalpha/keydb". */
    public static boolean isRedisImage(String image) {
        String repo = repository(image);
        return repo.equals("redis") || repo.startsWith("redis-stack") || repo.equals("valkey") || repo.equals("keydb");
    }

    /** "postgres:18.1", "docker.io/library/postgres", "postgis/postgis:16", "bitnami/postgresql". */
    public static boolean isPostgresImage(String image) {
        String repo = repository(image);
        return repo.equals("postgres")
                || repo.equals("postgresql")
                || repo.equals("postgis")
                || repo.startsWith("pgvector");
    }

    /** The last part of an image name without registry, owner, tag or digest: "docker.io/bitnami/redis:7" → "redis". */
    private static String repository(String image) {
        String name = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        int slash = name.lastIndexOf('/');
        int colon = name.lastIndexOf(':');
        return name.substring(slash + 1, colon > slash ? colon : name.length());
    }
}
