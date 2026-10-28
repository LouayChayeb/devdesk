package io.github.louch.devdesk.linux.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The wizard saves a devdesk.yaml whose env file Set up makes afterwards: before, it refused (Mastodon, Plausible). */
class LoadForSetupTest {

    @TempDir
    Path folder;

    private static final String YAML = """
            version: 1
            env_files: [.env]
            services:
              web:
                container: { image: mastodon }
                env_files: [.env.production]
            """;

    @Test
    void anEnvFileSetUpWillMakeDoesntStopTheWizardButStillStopsAStart() throws Exception {
        Files.writeString(folder.resolve(".env.production.sample"), "LOCAL_DOMAIN=example.com\n");

        assertThat(ProjectLoader.loadForSetup(folder, YAML, Map.of())).isInstanceOf(ProjectLoader.Result.Loaded.class);
        // Opening it for real still says what's missing: the app then offers Set up.
        assertThat(ProjectLoader.loadText(folder, YAML, Map.of()))
                .isInstanceOfSatisfying(
                        ProjectLoader.Result.Invalid.class,
                        i -> assertThat(i.message()).contains(".env.production").contains("Set up creates it"));
    }

    @Test
    void anEnvFileNobodyCanMakeIsStillAMistake() {
        // No example of .env.production: the wizard can't promise Set up will make it.
        assertThat(ProjectLoader.loadForSetup(folder, YAML, Map.of())).isInstanceOf(ProjectLoader.Result.Invalid.class);
    }
}
