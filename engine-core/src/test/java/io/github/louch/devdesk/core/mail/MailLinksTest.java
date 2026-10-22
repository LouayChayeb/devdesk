package io.github.louch.devdesk.core.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class MailLinksTest {

    @Test
    void linksComeFromHtmlThenTextOnceEachWithTheServiceTheyGoTo() throws Exception {
        var project = MailCatchersTest.project("""
                  api: { command: node server.js, ports: [3001] }
                """);
        String html =
                "<a href=\"http://localhost:3001/confirm?t=1&amp;u=2\">Confirm</a> <a href='https://example.com/x'>x</a>";
        String text = "Confirm: http://localhost:3001/confirm?t=1&u=2. Help: https://docs.example.com/help).";

        assertThat(MailLinks.of(text, html, project))
                .containsExactly(
                        new MailLinks.Link("http://localhost:3001/confirm?t=1&u=2", Optional.of("api")),
                        new MailLinks.Link("https://example.com/x", Optional.empty()),
                        new MailLinks.Link("https://docs.example.com/help", Optional.empty()));
    }
}
