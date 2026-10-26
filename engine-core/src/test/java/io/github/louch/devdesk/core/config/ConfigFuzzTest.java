package io.github.louch.devdesk.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Fuzz tests (Jazzer) for the files people write by hand: devdesk.yaml and .env. Whatever is in them, DevDesk must
 * answer with an error that points at the problem, never crash, and the .env editor must write what the reader reads.
 */
class ConfigFuzzTest {

    /** Any text as devdesk.yaml: a parsed project or a ConfigException, nothing else (no NPE, no stack overflow). */
    @FuzzTest(maxDuration = "10s")
    void devdeskYaml(FuzzedDataProvider data) {
        String yaml = data.consumeRemainingAsString();
        try {
            ConfigParser.parse(yaml, Path.of("/work/shop"), Map.of("HOME", "/home/me"), file -> Optional.of(yaml));
        } catch (ConfigException expected) {
            // a helpful error is the right answer to a broken file
        }
    }

    /** Any text as .env: some variables, never an exception (.env files in the wild hold all sorts of things). */
    @FuzzTest(maxDuration = "10s")
    void envFile(FuzzedDataProvider data) {
        String content = data.consumeRemainingAsString();
        EnvFile.parse(content);
    }

    /**
     * The app's .env editor: after {@code set(KEY, value)} the reader sees exactly that value, and every other
     * variable keeps the value it had.
     */
    @FuzzTest(maxDuration = "10s")
    void envEditorRoundTrip(FuzzedDataProvider data) {
        String key = data.pickValue(new String[] {"A", "DB_URL", "export", "_X1"});
        String content = data.consumeString(300);
        String value = data.consumeRemainingAsString();
        String written;
        try {
            written = EnvFileEdit.set(content, key, value);
        } catch (IllegalArgumentException lineBreak) {
            assertThat(value).containsAnyOf("\n", "\r");
            return;
        }
        Map<String, String> before = new LinkedHashMap<>(EnvFile.parse(content));
        Map<String, String> after = new LinkedHashMap<>(EnvFile.parse(written));
        assertThat(after.remove(key)).isEqualTo(value);
        before.remove(key);
        assertThat(after).isEqualTo(before);
    }
}
