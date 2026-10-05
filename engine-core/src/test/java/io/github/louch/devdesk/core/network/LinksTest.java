package io.github.louch.devdesk.core.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LinksTest {

    @Test
    void connectionsBecomeServiceToServiceLinksCountedOnce() {
        // web (PID 1) has 2 connections to api's port; api (PID 2) holds the accepted ends, plus 1 to db.
        var web = List.of(
                new Connection(1, "127.0.0.1", 50001, "127.0.0.1", 3001),
                new Connection(1, "127.0.0.1", 50002, "127.0.0.1", 3001));
        var api = List.of(
                new Connection(2, "127.0.0.1", 3001, "127.0.0.1", 50001),
                new Connection(2, "127.0.0.1", 3001, "127.0.0.1", 50002),
                new Connection(2, "::ffff:127.0.0.1", 40000, "::ffff:127.0.0.1", 5433));

        var links = Links.between(
                Map.of("web", web, "api", api), Map.of("web", Set.of(4200), "api", Set.of(3001), "db", Set.of(5433)));

        assertThat(links)
                .containsExactly(
                        new Links.Link("api", Optional.of("db"), "::ffff:127.0.0.1:5433", 1),
                        new Links.Link("web", Optional.of("api"), "127.0.0.1:3001", 2));
    }

    @Test
    void connectionsLeavingTheMachineAreOutside() {
        var api = List.of(new Connection(2, "10.0.0.5", 41000, "140.82.112.3", 443));

        var links = Links.between(Map.of("api", api), Map.of("api", Set.of(3001), "web", Set.of(443)));

        // web listens on 443 locally, but this connection goes to another machine: not web.
        assertThat(links).containsExactly(new Links.Link("api", Optional.empty(), "140.82.112.3:443", 1));
    }
}
