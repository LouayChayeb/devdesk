package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ActiveProfilesTest {

    @TempDir
    Path state;

    @Test
    void eachProjectRemembersItsOwnProfileAndEmptyMeansDefault() throws Exception {
        var profiles = new ActiveProfiles(state.resolve("devdesk/profiles.properties"));

        profiles.set("shop-1234", Optional.of("demo"));
        profiles.set("blog-5678", Optional.of("test"));
        profiles.set("blog-5678", Optional.empty());

        var again = new ActiveProfiles(state.resolve("devdesk/profiles.properties")); // read back from the file
        assertThat(again.of("shop-1234")).contains("demo");
        assertThat(again.of("blog-5678")).isEmpty();
        assertThat(again.of("never-seen")).isEmpty();
    }

    @Test
    void aBrokenFileMeansDefaultNotAnError() throws Exception {
        Path file = state.resolve("profiles.properties");
        Files.write(file, new byte[] {(byte) 0xff, 'a', '=', 'b'});
        Path escape = state.resolve("escape.properties");
        Files.writeString(escape, "shop=\\uzzzz\n"); // a broken \\u escape: Properties refuses it

        assertThat(new ActiveProfiles(file).of("shop")).isEmpty();
        assertThat(new ActiveProfiles(escape).of("shop")).isEmpty();
    }
}
