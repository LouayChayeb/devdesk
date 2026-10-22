package io.github.louch.devdesk.core.watch;

import java.nio.file.Path;
import java.util.Set;

/**
 * Which files never trigger a restart: dependencies, build output, caches and editor temp files. Without these, an
 * "npm install" or a build would restart a service hundreds of times, or a service writing its own logs would restart
 * itself forever.
 */
public final class WatchRules {

    /** Folders skipped entirely (not even walked). */
    public static final Set<String> SKIPPED_FOLDERS = Set.of(
            "node_modules",
            ".git",
            ".hg",
            ".svn",
            "build",
            "dist",
            "target",
            "out",
            ".gradle",
            ".next",
            ".nuxt",
            ".angular",
            ".cache",
            "__pycache__",
            ".venv",
            "venv",
            ".mypy_cache",
            ".pytest_cache",
            "coverage",
            ".idea",
            ".vscode",
            ".devdesk",
            "logs",
            "tmp");

    private WatchRules() {}

    /**
     * Whether a change to this file should be ignored.
     *
     * @param file relative to the watched folder: a project that itself lives under ~/build/ must still be watched
     */
    public static boolean ignored(Path file) {
        for (Path part : file) {
            if (SKIPPED_FOLDERS.contains(part.toString())) {
                return true;
            }
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        // Editors save through temp files (vim: .swp/4913, emacs: #x#, JetBrains: ___jb_tmp___), logs grow by
        // themselves, and compiled Python is written when the service starts.
        return name.endsWith(".swp")
                || name.endsWith(".swx")
                || name.endsWith("~")
                || name.equals("4913")
                || name.startsWith(".#")
                || name.startsWith("#")
                || name.contains("___jb_")
                || name.endsWith(".log")
                || name.endsWith(".pyc")
                || name.endsWith(".tmp")
                || name.equals(".DS_Store");
    }
}
