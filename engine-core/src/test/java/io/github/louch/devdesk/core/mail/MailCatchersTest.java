package io.github.louch.devdesk.core.mail;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MailCatchersTest {

    static ProjectSpec project(String services) throws Exception {
        return ConfigParser.parse("version: 1\nservices:\n" + services, Path.of("/p"), Map.of(), f -> Optional.empty());
    }

    @Test
    void aMailpitContainerIsFoundByItsImageAndPublishedPorts() throws Exception {
        var p = project("""
                  mail: { container: { image: "axllent/mailpit:v1.21", ports: ["58026:8025", "51026:1025"] } }
                  db: { container: { image: postgres:18.1, ports: ["55432:5432"] } }
                """);

        assertThat(MailCatchers.find(p)).containsExactly(new MailCatcher("mail", "http://127.0.0.1:58026", 51026));
    }

    @Test
    void withoutAPublishedWebPortThereIsNothingToRead() throws Exception {
        var p = project("""
                  mail: { container: { image: axllent/mailpit, ports: ["51026:1025"] } }
                """);

        assertThat(MailCatchers.find(p)).isEmpty();
    }

    @Test
    void aNativeMailpitUsesItsFlagsThenItsVariablesThenDefaults() throws Exception {
        var p = project("""
                  a: { command: "./bin/mailpit --listen 127.0.0.1:9025 -s :2525" }
                  b: { command: mailpit, environment: { MP_UI_BIND_ADDR: "0.0.0.0:7025" } }
                  c: { command: mailpit }
                  d: { command: "node mailpit.js" }
                """);

        assertThat(MailCatchers.find(p))
                .containsExactlyInAnyOrder(
                        new MailCatcher("a", "http://127.0.0.1:9025", 2525),
                        new MailCatcher("b", "http://127.0.0.1:7025", 1025),
                        new MailCatcher("c", "http://127.0.0.1:8025", 1025));
    }
}
