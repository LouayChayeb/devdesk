package io.github.louch.devdesk.core.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class DatabaseTemplatesTest {

    private final RandomGenerator random = RandomGenerator.of("L64X128MixRandom");

    @Test
    void theUsualPortWhenFreeElseTheNextFreeOne() {
        var pg = DatabaseTemplates.create("postgres", "My Shop", Set.of(), Set.of(), p -> p != 5432, random)
                .orElseThrow();

        assertThat(pg.ports()).containsExactly("5433:5432");
        assertThat(pg.environment().get("POSTGRES_PASSWORD")).hasSize(20);
        assertThat(pg.environment()).containsEntry("POSTGRES_DB", "my_shop");
        assertThat(pg.health().target())
                .isEqualTo("docker exec devdesk-My_Shop-postgres pg_isready -h 127.0.0.1 -U postgres");
    }

    @Test
    void namesAndPortsAlreadyInTheDraftAreSkipped() {
        var redis = DatabaseTemplates.create("redis", "x", Set.of("redis"), Set.of(6379), p -> true, random)
                .orElseThrow();
        var mail = DatabaseTemplates.create("mailpit", "x", Set.of(), Set.of(), p -> true, random)
                .orElseThrow();

        assertThat(redis.name()).isEqualTo("redis-2");
        assertThat(redis.ports()).containsExactly("6380:6379");
        assertThat(mail.ports()).containsExactly("8025:8025", "1025:1025");
        assertThat(mail.health().target()).isEqualTo("http://127.0.0.1:8025/readyz");
    }

    @Test
    void theMysqlPasswordStaysOutOfTheCommandList() {
        var mysql = DatabaseTemplates.create("mysql", "x", Set.of(), Set.of(), p -> true, random)
                .orElseThrow();

        assertThat(mysql.health().target())
                .contains("$MYSQL_ROOT_PASSWORD")
                .doesNotContain(mysql.environment().get("MYSQL_ROOT_PASSWORD"));
        assertThat(DatabaseTemplates.create("oracle", "x", Set.of(), Set.of(), p -> true, random))
                .isEmpty();
    }
}
