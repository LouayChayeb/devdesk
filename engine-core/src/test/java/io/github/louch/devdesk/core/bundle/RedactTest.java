package io.github.louch.devdesk.core.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedactTest {

    @Test
    void secretSettingsLoseTheirValues() {
        String yaml = """
                services:
                  api:
                    environment:
                      DB_PASSWORD: hunter22
                      PORT: "3000"
                      DATABASE_URL: postgres://app:s3cr3t@localhost:5432/shop
                    command: node server.js
                """;
        String out = Redact.settings(yaml);
        assertThat(out).contains("DB_PASSWORD: ***", "PORT: \"3000\"", "command: node server.js");
        assertThat(out).doesNotContain("hunter22", "s3cr3t");
        assertThat(Redact.settings("JWT_SECRET=abc123def")).isEqualTo("JWT_SECRET=***");
        // Several settings on one line, in YAML's inline form.
        assertThat(Redact.settings("      environment: { POSTGRES_PASSWORD: dev, POSTGRES_DB: shop }"))
                .isEqualTo("      environment: { POSTGRES_PASSWORD: ***, POSTGRES_DB: shop }");
        assertThat(Redact.settings("  api_key: \"abc, def\"  # note")).doesNotContain("abc");
    }

    @Test
    void secretValuesAreHiddenWhereverTheyAppear() {
        String log = "connecting with token tok_123456 to postgres://u:pw999999@db:5432/x, again tok_123456";
        String out = Redact.values(log, List.of("tok_123456", "pw999999", "a"));
        assertThat(out).doesNotContain("tok_123456", "pw999999").contains("***");
        assertThat(Redact.values("a b c", List.of("a"))).isEqualTo("a b c"); // too short to hide safely
    }
}
