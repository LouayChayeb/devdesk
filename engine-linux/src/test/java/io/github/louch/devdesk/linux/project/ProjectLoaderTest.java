package io.github.louch.devdesk.linux.project;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.linux.trust.TrustStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectLoaderTest {

    @TempDir
    Path dir;

    private static final String YAML = "version: 1\nname: %s\nservices:\n  api: { command: sleep 1 }\n";

    @Test
    void aFileNamedWithDashFKeepsItsOwnName() throws Exception {
        Path crash = Files.writeString(dir.resolve("crash.yaml"), YAML.formatted("crash"));

        var loaded = (ProjectLoader.Result.Loaded) ProjectLoader.load(crash, Map.of());

        assertThat(loaded.file()).isEqualTo(crash);
        assertThat(loaded.project().root()).isEqualTo(dir);
    }

    @Test
    void trustingOneFileOfAFolderDoesNotUntrustAnother() throws Exception {
        // Found with a folder of several broken-on-purpose project files, each trusted with -f.
        Path a = Files.writeString(dir.resolve("a.yaml"), YAML.formatted("a"));
        Path b = Files.writeString(dir.resolve("b.yaml"), YAML.formatted("b"));
        var store = new TrustStore(dir.resolve("trust.txt"));
        var la = (ProjectLoader.Result.Loaded) ProjectLoader.load(a, Map.of());
        var lb = (ProjectLoader.Result.Loaded) ProjectLoader.load(b, Map.of());

        store.trust(la.file(), TrustStore.fingerprint(la.content()));
        store.trust(lb.file(), TrustStore.fingerprint(lb.content()));

        assertThat(store.isTrusted(la.file(), TrustStore.fingerprint(la.content())))
                .isTrue();
        assertThat(store.isTrusted(lb.file(), TrustStore.fingerprint(lb.content())))
                .isTrue();
    }

    @Test
    void aFolderStillMeansItsDevdeskYaml() throws Exception {
        Files.writeString(dir.resolve("devdesk.yaml"), YAML.formatted("app"));

        var loaded = (ProjectLoader.Result.Loaded) ProjectLoader.load(dir, Map.of());

        assertThat(loaded.file()).isEqualTo(dir.resolve("devdesk.yaml"));
    }
}
