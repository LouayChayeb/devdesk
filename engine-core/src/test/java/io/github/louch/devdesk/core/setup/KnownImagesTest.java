package io.github.louch.devdesk.core.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KnownImagesTest {

    @Test
    void mariaDbIsAskedWithMariadbAdminWhichNewImagesHaveInsteadOfMysqladmin() {
        // Found on directus: mariadb:12.3 has no mysqladmin, so "maria" never counted as healthy and blocked the api.
        var maria = KnownImages.health("mariadb:12.3", "devdesk-directus-maria", List.of())
                .orElseThrow();
        var mysql = KnownImages.health("mysql:9", "devdesk-shop-db", List.of()).orElseThrow();

        assertThat(maria.target())
                .isEqualTo("docker exec devdesk-directus-maria sh -c 'mariadb-admin ping -h 127.0.0.1 --silent"
                        + " || mysqladmin ping -h 127.0.0.1 --silent'");
        assertThat(mysql.target()).isEqualTo("docker exec devdesk-shop-db mysqladmin ping -h 127.0.0.1 --silent");
    }
}
