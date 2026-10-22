package io.github.louch.devdesk.core.network;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.network.PortTable.Owner;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PortTableTest {

    private static Listener on(int port, long pid, String command) {
        return new Listener(port, "127.0.0.1", 1000, Optional.of(pid), Optional.of(command));
    }

    @Test
    void eachPortSaysWhoOwnsItAndConflictsComeFirst() throws Exception {
        var project = ConfigParser.parse("""
                version: 1
                name: shop
                services:
                  api: { command: node server.js, ports: [3001] }
                  web: { command: vite, ports: [5173] }
                  db: { container: { image: "postgres:16", ports: ["5432:5432"] }, ports: [5432] }
                """, Path.of("/p"), Map.of(), f -> Optional.empty());
        var rows = PortTable.of(
                project,
                Map.of("api", Set.of(10L)),
                List.of(
                        on(3001, 10, "node server.js"),
                        on(5173, 20, "vite"), // web's port, held by something else
                        on(5432, 30, "/usr/bin/docker-proxy -host-port 5432"),
                        on(8080, 40, "java -jar other.jar"),
                        on(9000, 50, "python3 x.py"),
                        on(41000, 99, "java devdesk")),
                pid -> pid == 40 ? Optional.of("blog-1a2b3c4d") : Optional.empty(),
                99);

        assertThat(rows)
                .extracting(r ->
                        r.listener().port() + " " + r.owner() + " " + r.who().orElse("-") + " " + r.conflict())
                .containsExactly(
                        "5173 NOT_DEVDESK - true",
                        "3001 THIS_PROJECT api false",
                        "5432 THIS_PROJECT db false",
                        "8080 OTHER_DEVDESK_PROJECT blog-1a2b3c4d false",
                        "9000 NOT_DEVDESK - false",
                        "41000 DEVDESK - false");
        assertThat(rows.getFirst().owner()).isEqualTo(Owner.NOT_DEVDESK);
    }
}
