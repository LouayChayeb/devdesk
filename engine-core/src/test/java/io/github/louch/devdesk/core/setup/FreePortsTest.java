package io.github.louch.devdesk.core.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FreePortsTest {

    private static SetupDraft.Service service(String name, String kind, List<String> ports, SetupDraft.Health health) {
        return new SetupDraft.Service(
                name, kind, "", "", "img", ports, List.of(), Map.of(), Map.of(), health, "no", "", List.of());
    }

    @Test
    void twoCardsOnOnePortDontCollideTheContainerMovesAndCommandsAreWarned() {
        // Found on excalidraw: its docs site (docusaurus, a command) and its app's container both wanted port 3000,
        // free on the machine, so nothing moved and the container failed at start.
        var draft = new SetupDraft(
                "excalidraw",
                List.of(),
                List.of(
                        service("app", "container", List.of("3000:80"), SetupDraft.Health.tcp(3000)),
                        service("dev-docs", "command", List.of("3000"), SetupDraft.Health.tcp(3000)),
                        service("storybook", "command", List.of("3000"), SetupDraft.Health.tcp(3000))),
                List.of());

        var after = FreePorts.apply(draft, port -> true).services();

        assertThat(after.get(0).ports()).containsExactly("3001:80");
        assertThat(after.get(0).health().target()).isEqualTo("3001");
        assertThat(after.get(0).notes()).anyMatch(n -> n.startsWith("Port 3000 is taken by dev-docs (another card)"));
        assertThat(after.get(1).notes()).isEmpty();
        assertThat(after.get(2).ports()).containsExactly("3000");
        assertThat(after.get(2).notes()).anyMatch(n -> n.startsWith("Port 3000 is also dev-docs's"));
    }

    @Test
    void aTakenHostPortMovesToTheNextFreeOneAndTheCardSaysWhere() {
        Set<Integer> taken = Set.of(8080, 8081, 5432);
        var draft = new SetupDraft(
                "shop",
                List.of(),
                List.of(
                        service(
                                "web",
                                "container",
                                List.of("8080:80"),
                                SetupDraft.Health.http("http://127.0.0.1:8080/")),
                        service("admin", "container", List.of("8082:80"), SetupDraft.Health.tcp(8082)),
                        service("db", "container", List.of("127.0.0.1:5432:5432"), SetupDraft.Health.tcp(5432)),
                        service("api", "command", List.of("5432"), SetupDraft.Health.tcp(5432))),
                List.of());

        var after = FreePorts.apply(draft, port -> !taken.contains(port)).services();

        // 8081 is taken too, and 8082 belongs to admin: web gets 8083.
        assertThat(after.get(0).ports()).containsExactly("8083:80");
        assertThat(after.get(0).health().target()).isEqualTo("http://127.0.0.1:8083/");
        assertThat(after.get(0).notes()).anyMatch(n -> n.contains("open it at http://127.0.0.1:8083/"));
        assertThat(after.get(1).ports()).containsExactly("8082:80");
        assertThat(after.get(2).ports()).containsExactly("127.0.0.1:5433:5432");
        assertThat(after.get(2).health().target()).isEqualTo("5433");
        // A command's port is its own setting: said, not changed.
        assertThat(after.get(3).ports()).containsExactly("5432");
        assertThat(after.get(3).notes()).anyMatch(n -> n.startsWith("Port 5432 is taken"));
    }
}
