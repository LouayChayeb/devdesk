package io.github.louch.devdesk.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

/** YAML anchors, aliases and merge keys, as Chatwoot's compose file uses them. */
class YamlMergeKeysTest {

    private static final String CHATWOOT = """
            services:
              base: &base
                image: chatwoot/chatwoot:v4.19.1
                env_file: .env
                volumes:
                  - storage_data:/app/storage
              rails:
                <<: *base
                ports: ['127.0.0.1:3000:3000']
                command: ['bundle', 'exec', 'rails', 's']
              sidekiq:
                <<: *base
                image: chatwoot/chatwoot:custom
            """;

    private static YamlNode.Mapping service(YamlNode root, String name) {
        var services = (YamlNode.Mapping)
                ((YamlNode.Mapping) root).entries().get("services").value();
        return (YamlNode.Mapping) services.entries().get(name).value();
    }

    private static String text(YamlNode.Mapping m, String key) {
        return ((YamlNode.Scalar) m.entries().get(key).value()).text();
    }

    @Test
    void aServiceGetsWhatItsMergeKeyNamesAndItsOwnKeysWin() throws Exception {
        var root = YamlReader.read(CHATWOOT);

        var rails = service(root, "rails");
        assertThat(text(rails, "image")).isEqualTo("chatwoot/chatwoot:v4.19.1"); // lost before: no image at all
        assertThat(rails.entries()).containsKeys("env_file", "volumes", "ports", "command");
        assertThat(text(service(root, "sidekiq"), "image")).isEqualTo("chatwoot/chatwoot:custom");
    }

    @Test
    void anAliasToNothingIsSaidWithItsLine() {
        var e = catchThrowableOfType(ConfigException.class, () -> YamlReader.read("a:\n  <<: *nothing\n"));

        assertThat(e.problems()).singleElement().satisfies(p -> {
            assertThat(p.message()).isEqualTo("*nothing refers to no &nothing above it");
            assertThat(p.location().line()).isEqualTo(2);
        });
    }

    @Test
    void textTheYamlLibraryChokesOnIsAnErrorNotACrash() {
        // Fuzzing found it: a lone high surrogate just past the library's first 1024 characters made it read past
        // its own array (IndexOutOfBoundsException). From disk this can't happen (UTF-8 decoding never makes one).
        String text = "#" + "a".repeat(1023) + "\uD800" + "b: c\n".repeat(10);

        var e = catchThrowableOfType(ConfigException.class, () -> YamlReader.read(text));

        assertThat(e.problems())
                .singleElement()
                .satisfies(p -> assertThat(p.message())
                        .isEqualTo("not valid YAML: the file has characters the YAML reader can't read"));
    }
}
