package io.github.louch.devdesk.linux.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

/**
 * Makes {@code pnpm} and {@code yarn} reachable by name through corepack, which comes with Node, without installing
 * anything globally. Projects' own scripts call them by name (directus: {@code "build": "pnpm --recursive run build"}),
 * so the {@code corepack pnpm} DevDesk writes in front of a card's command isn't enough. Used by the engine and the
 * CLI when they start, for every setup step and service.
 */
public final class CorepackShims {

    private CorepackShims() {}

    /**
     * {@code env} with a folder of corepack's launchers ({@code <state>/devdesk/corepack-bin}) at the end of PATH,
     * made the first time. At the end, so a pnpm or yarn the user installed still wins; the launchers only fill the
     * gap. Unchanged when corepack isn't on PATH or making the launchers fails.
     */
    public static Map<String, String> addTo(Map<String, String> env) {
        String state = env.getOrDefault("XDG_STATE_HOME", "");
        Path base = state.isBlank() ? Path.of(env.getOrDefault("HOME", "/tmp"), ".local", "state") : Path.of(state);
        return addTo(env, base.resolve("devdesk").resolve("corepack-bin"), CorepackShims::install);
    }

    /** @param install makes the launchers (corepack, the folder); false when it couldn't */
    static Map<String, String> addTo(Map<String, String> env, Path dir, BiPredicate<Path, Path> install) {
        String path = env.getOrDefault("PATH", "");
        Optional<Path> corepack = find("corepack", path);
        // The launchers are links into one Node's corepack: after that Node is removed (nvm), the link is broken,
        // exists() follows it and says false, and they're made again for the Node on PATH now.
        boolean ready = Files.exists(dir.resolve("pnpm"));
        if (corepack.isEmpty() || !(ready || install.test(corepack.get(), dir))) {
            return env;
        }
        Map<String, String> out = new HashMap<>(env);
        out.put("PATH", path.isEmpty() ? dir.toString() : path + ":" + dir);
        return Map.copyOf(out);
    }

    private static boolean install(Path corepack, Path dir) {
        try {
            Files.createDirectories(dir);
            Process p = new ProcessBuilder(List.of(
                            corepack.toString(), "enable", "--install-directory", dir.toString(), "pnpm", "yarn"))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            // Only links are written: it takes well under a second. A stuck corepack must not stop DevDesk starting.
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Optional<Path> find(String program, String path) {
        for (String dir : path.split(":")) {
            if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, program))) {
                return Optional.of(Path.of(dir, program));
            }
        }
        return Optional.empty();
    }
}
