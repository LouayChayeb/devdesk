package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.setup.SetupDraft;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SetupJsonTest {

    @Test
    void aDraftSurvivesTheTripToTheAppAndBack() {
        var api = new SetupDraft.Service(
                "api",
                "command",
                "npm run dev",
                "api",
                "",
                List.of("3000"),
                List.of(),
                Map.of("PORT", "3000"),
                Map.of("db", "healthy"),
                SetupDraft.Health.tcp(3000),
                "on-failure",
                "api/package.json",
                List.of("a note"),
                Map.of("stop_grace_period", "30s"));
        var draft = new SetupDraft("shop", List.of(".env"), List.of(api), List.of());

        // Through JSON text, like the real request: numbers and maps come back as Jackson reads them.
        Object json = ApiJson.MAPPER.readValue(ApiJson.write(SetupJson.draft(draft)), Map.class);

        assertThat(SetupJson.readDraft(json)).contains(draft);
    }

    @Test
    void wrongShapesAreRefusedNotGuessed() {
        assertThat(SetupJson.readDraft(Map.of("services", "api"))).isEmpty();
        assertThat(SetupJson.readDraft(Map.of("services", List.of(Map.of("kind", "command")))))
                .isEmpty(); // no name
        assertThat(SetupJson.readDraft("text")).isEmpty();
    }

    @Test
    void aPortTypedAsANumberIsAcceptedAndAnUnknownKindIsACommand() {
        var draft = SetupJson.readDraft(Map.of(
                        "name", "x", "services", List.of(Map.of("name", "web", "kind", "vm", "ports", List.of(8080)))))
                .orElseThrow();

        assertThat(draft.services().getFirst().ports()).containsExactly("8080");
        assertThat(draft.services().getFirst().kind()).isEqualTo("command");
    }
}
