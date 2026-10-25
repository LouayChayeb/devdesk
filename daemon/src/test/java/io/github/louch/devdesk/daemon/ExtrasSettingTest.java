package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The extras switch as plain Java: where it's saved, and who wins (ExtrasTest checks the routes on a real engine). */
class ExtrasSettingTest {

    @TempDir
    Path home;

    private Map<String, String> env(String... pairs) {
        Map<String, String> m = new HashMap<>(Map.of("HOME", home.toString()));
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    void offByDefault() {
        assertThat(Extras.enabled(env())).isFalse();
        assertThat(Extras.saved(env())).isEmpty();
        assertThat(Extras.forcedByEnvironment(env())).isFalse();
    }

    @Test
    void theSavedSettingTurnsThemOnAndKeepsOtherKeys() throws Exception {
        Path file = Extras.file(env());
        Files.createDirectories(file.getParent());
        Files.writeString(file, "theme=dark\n");

        Extras.save(env(), true);

        assertThat(Extras.enabled(env())).isTrue();
        assertThat(Extras.saved(env())).contains(true);
        assertThat(Files.readString(file)).contains("theme=dark").contains("extras=on");
        assertThat(file).isEqualTo(home.resolve(".config/devdesk/settings.properties"));
        assertThat(file.resolveSibling("settings.properties.tmp")).doesNotExist();

        Extras.save(env(), false);
        assertThat(Extras.saved(env())).contains(false);
        assertThat(Files.readString(file)).contains("extras=off").contains("theme=dark");
    }

    @Test
    void theEnvVarWinsOverTheSettingAndNonsenseIsIgnored() throws Exception {
        Extras.save(env(), true);

        assertThat(Extras.enabled(env(Extras.ENV, "off"))).isFalse();
        assertThat(Extras.forcedByEnvironment(env(Extras.ENV, "off"))).isTrue();
        assertThat(Extras.enabled(env(Extras.ENV, "maybe"))).isTrue(); // not on/off: the setting decides
        assertThat(Extras.forcedByEnvironment(env(Extras.ENV, "maybe"))).isFalse();
        assertThat(Extras.enabled(
                        env("XDG_CONFIG_HOME", home.resolve("elsewhere").toString())))
                .isFalse();
    }

    @Test
    void everySpellingOfOnAndOff() {
        for (String on : new String[] {"on", "true", "1", "yes", " ON ", "Yes"}) {
            assertThat(Extras.enabled(env(Extras.ENV, on))).as(on).isTrue();
        }
        for (String off : new String[] {"off", "false", "0", "no", "NO"}) {
            assertThat(Extras.enabled(env(Extras.ENV, off))).as(off).isFalse();
            assertThat(Extras.forcedByEnvironment(env(Extras.ENV, off))).as(off).isTrue();
        }
    }

    @Test
    void xdgConfigHomeMovesTheFileAndAnUnreadableFileCountsAsUnset() throws Exception {
        var env = env("XDG_CONFIG_HOME", home.resolve("cfg").toString());
        assertThat(Extras.file(env)).isEqualTo(home.resolve("cfg/devdesk/settings.properties"));
        assertThat(Extras.file(env("XDG_CONFIG_HOME", " ")))
                .isEqualTo(home.resolve(".config/devdesk/settings.properties"));

        Files.createDirectories(Extras.file(env).getParent());
        Files.writeString(Extras.file(env), "extras=\\u12\n"); // a broken escape: Properties.load throws
        assertThat(Extras.saved(env)).isEmpty();
        Files.writeString(Extras.file(env), "extras=sometimes\n");
        assertThat(Extras.saved(env)).isEmpty();
    }
}
