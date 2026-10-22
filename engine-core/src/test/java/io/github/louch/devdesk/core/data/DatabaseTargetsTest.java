package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DatabaseTargetsTest {

    private static ProjectSpec project(String services) throws Exception {
        return ConfigParser.parse("version: 1\nservices:\n" + services, Path.of("/p"), Map.of(), f -> Optional.empty());
    }

    @Test
    void postgresContainerIsFoundWithItsPortUserPasswordAndDatabase() throws Exception {
        var p = project("""
                  db:
                    container:
                      image: postgres:18.1
                      ports: ["55440:5432"]
                      environment: { POSTGRES_PASSWORD: dev, POSTGRES_DB: shop, POSTGRES_USER: app }
                  api: { command: node server.js }
                """);

        assertThat(DatabaseTargets.find(p))
                .containsExactly(new DatabaseTarget(
                        "db", DatabaseTarget.Kind.POSTGRES, "127.0.0.1", 55440, "app", "dev", "shop"));
    }

    @Test
    void aPasswordKeptInAnEnvFileOpensTheDatabaseToo() throws Exception {
        // The container gets POSTGRES_PASSWORD from .env (env_files); the Data page must use the same value.
        var p = ConfigParser.parse(
                """
                version: 1
                env_files: [.env]
                services:
                  db: { container: { image: postgres:18, ports: ["5432:5432"] } }
                """,
                Path.of("/p"),
                Map.of(),
                f -> f.equals(".env")
                        ? Optional.of("POSTGRES_PASSWORD=from-env\nPOSTGRES_DB=shop\n")
                        : Optional.empty());

        var t = DatabaseTargets.find(p, "db").orElseThrow();

        assertThat(t.password()).isEqualTo("from-env");
        assertThat(t.database()).isEqualTo("shop");
        assertThat(DatabaseTargets.find(p)).containsExactly(t);
    }

    @Test
    void mongoContainerIsFoundWithItsRootUser() throws Exception {
        var p = project("""
                  docs:
                    container:
                      image: mongo:8
                      ports: ["57017:27017"]
                      environment: { MONGO_INITDB_ROOT_USERNAME: root, MONGO_INITDB_ROOT_PASSWORD: dev }
                """);

        assertThat(DatabaseTargets.find(p))
                .containsExactly(
                        new DatabaseTarget("docs", DatabaseTarget.Kind.MONGO, "127.0.0.1", 57017, "root", "dev", ""));
        assertThat(DatabaseTarget.Kind.MONGO.isSql()).isFalse();
    }

    @Test
    void theOfficialImageDefaultsApply() throws Exception {
        var p = project("""
                  pg: { container: { image: "docker.io/library/postgres:16-alpine", ports: ["15432:5432"] } }
                """);

        var t = DatabaseTargets.find(p, "pg").orElseThrow();

        assertThat(t.user()).isEqualTo("postgres");
        assertThat(t.database()).isEqualTo("postgres"); // POSTGRES_DB defaults to the user's name
    }

    @Test
    void notPublishedOrNotADatabaseIsNotATarget() throws Exception {
        var p = project("""
                  hidden: { container: { image: postgres:18.1 } }
                  web: { container: { image: nginx:1, ports: ["8080:80"] } }
                """);

        assertThat(DatabaseTargets.find(p)).isEmpty();
    }

    @Test
    void redisIsFoundWithThePasswordFromItsCommandOrEnvironment() throws Exception {
        var p = project("""
                  cache:
                    container: { image: "redis:8", ports: ["16379:6379"], command: "redis-server --requirepass s3cret" }
                  queue:
                    container: { image: "bitnami/redis:7", ports: ["127.0.0.1:16380:6379"], environment: { REDIS_PASSWORD: pw } }
                  plain: { container: { image: "valkey/valkey:8", ports: ["16381:6379"] } }
                """);

        assertThat(DatabaseTargets.find(p))
                .containsExactly(
                        new DatabaseTarget("cache", DatabaseTarget.Kind.REDIS, "127.0.0.1", 16379, "", "s3cret", "0"),
                        new DatabaseTarget("queue", DatabaseTarget.Kind.REDIS, "127.0.0.1", 16380, "", "pw", "0"),
                        new DatabaseTarget("plain", DatabaseTarget.Kind.REDIS, "127.0.0.1", 16381, "", "", "0"));
    }

    @Test
    void imageNamesAreRecognised() {
        assertThat(DatabaseTargets.isPostgresImage("postgres")).isTrue();
        assertThat(DatabaseTargets.isPostgresImage("postgis/postgis:16-3.4")).isTrue();
        assertThat(DatabaseTargets.isPostgresImage("bitnami/postgresql:17")).isTrue();
        assertThat(DatabaseTargets.isPostgresImage("ghcr.io/me/postgres-exporter:1"))
                .isFalse();
        assertThat(DatabaseTargets.isPostgresImage("mysql:9.5")).isFalse();
        assertThat(DatabaseTargets.isRedisImage("redis/redis-stack-server:7.4")).isTrue();
        assertThat(DatabaseTargets.isRedisImage("oliver006/redis_exporter")).isFalse();
    }

    @Test
    void thePasswordNeverShowsInText() {
        var t = new DatabaseTarget("db", DatabaseTarget.Kind.POSTGRES, "127.0.0.1", 5432, "app", "s3cret", "shop");

        assertThat(t.toString()).doesNotContain("s3cret").contains("app@127.0.0.1:5432/shop");
    }

    @Test
    void mysqlAndMariadbUseRootWhenItsPasswordIsKnownElseTheAppUser() throws Exception {
        var p = project("""
                  my:
                    container:
                      image: mysql:8.4
                      ports: ["13306:3306"]
                      environment: { MYSQL_ROOT_PASSWORD: rootpw, MYSQL_DATABASE: shop, MYSQL_USER: app, MYSQL_PASSWORD: apppw }
                  maria:
                    container:
                      image: mariadb:11
                      ports: ["13307:3306"]
                      environment: { MARIADB_RANDOM_ROOT_PASSWORD: "1", MARIADB_USER: app, MARIADB_PASSWORD: pw, MARIADB_DATABASE: crm }
                """);

        assertThat(DatabaseTargets.find(p))
                .containsExactly(
                        new DatabaseTarget(
                                "my", DatabaseTarget.Kind.MYSQL, "127.0.0.1", 13306, "root", "rootpw", "shop"),
                        new DatabaseTarget("maria", DatabaseTarget.Kind.MYSQL, "127.0.0.1", 13307, "app", "pw", "crm"));
    }
}
