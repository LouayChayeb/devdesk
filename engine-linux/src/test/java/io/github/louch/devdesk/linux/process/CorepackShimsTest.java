package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorepackShimsTest {

    @TempDir
    Path dir;

    private final List<Path> installs = new ArrayList<>();

    private Path nodeBin() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("node/bin"));
        Files.writeString(bin.resolve("corepack"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(bin.resolve("corepack"), PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin;
    }

    @Test
    void theLaunchersAreMadeOnceAndAddedAtTheEndOfPath() throws Exception {
        // Found on directus: its build script calls plain "pnpm", which only corepack provides on this machine.
        Path bin = nodeBin();
        Path shims = dir.resolve("corepack-bin");

        var env = CorepackShims.addTo(Map.of("PATH", bin + ":/usr/bin"), shims, (corepack, to) -> {
            installs.add(corepack);
            return true;
        });

        assertThat(installs).containsExactly(bin.resolve("corepack"));
        // last: a pnpm or yarn the user installed is still found first
        assertThat(env.get("PATH")).isEqualTo(bin + ":/usr/bin:" + shims);
    }

    @Test
    void launchersAlreadyThereAreNotMadeAgain() throws Exception {
        Path bin = nodeBin();
        Path shims = Files.createDirectories(dir.resolve("corepack-bin"));
        Files.writeString(shims.resolve("pnpm"), "");

        var env = CorepackShims.addTo(Map.of("PATH", bin.toString()), shims, (corepack, to) -> installs.add(to));

        assertThat(installs).isEmpty();
        assertThat(env.get("PATH")).endsWith(":" + shims);
    }

    @Test
    void withoutCorepackOrWhenMakingThemFailsNothingChanges() throws Exception {
        var noNode = Map.of("PATH", "/usr/bin");
        assertThat(CorepackShims.addTo(noNode, dir.resolve("x"), (c, t) -> installs.add(t)))
                .isEqualTo(noNode);
        assertThat(installs).isEmpty();

        var withNode = Map.of("PATH", nodeBin().toString());
        assertThat(CorepackShims.addTo(withNode, dir.resolve("x"), (c, t) -> false))
                .isEqualTo(withNode);
    }
}
