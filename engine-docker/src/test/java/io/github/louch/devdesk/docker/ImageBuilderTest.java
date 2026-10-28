package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImageBuilderTest {

    @Test
    void theCommandIsWhatADeveloperWouldTypeWithDevDesksLabels() {
        var build = new Workload.Build(Path.of("/work/shop"), "api/Dockerfile");

        assertThat(ImageBuilder.command("/usr/bin/docker", "shop-api", build, Map.of("devdesk.project", "shop"), false))
                .containsExactly(
                        "/usr/bin/docker",
                        "build",
                        "--progress=plain",
                        "-t",
                        "shop-api",
                        "-f",
                        "/work/shop/api/Dockerfile",
                        "--label",
                        "devdesk.project=shop",
                        "/work/shop");
    }

    @Test
    void aFailedStepIsNamedWithItsLikelyCause() {
        String why = ImageBuilder.explain(
                "shop-api",
                "3/5 (RUN npm ci)",
                List.of(
                        "#9 [3/5] RUN npm ci",
                        "#9 0.8 npm ERR! missing script",
                        "ERROR: failed to solve: process \"/bin/sh -c npm ci\" did not complete successfully: exit code: 1"));

        assertThat(why)
                .startsWith(
                        "building image shop-api failed at step 3/5 (RUN npm ci). A command in the Dockerfile failed")
                .contains("npm ERR! missing script");
    }

    @Test
    void aMissingFileAndAFullDiskAreSaidPlainly() {
        assertThat(ImageBuilder.explain(
                        "web",
                        "",
                        List.of(
                                "ERROR: failed to solve: failed to compute cache key: \"/dist\": not found",
                                "COPY dist /usr/share/nginx/html")))
                .contains("copies a file that isn't in the folder");
        assertThat(ImageBuilder.explain("web", "", List.of("write /var/lib/x: no space left on device")))
                .contains("The disk is full");
    }
}
