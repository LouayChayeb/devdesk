package io.github.louch.devdesk.data;

import java.nio.file.Path;
import java.util.Map;

/** DevDesk's own data folder: {@code $XDG_STATE_HOME/devdesk}, or {@code ~/.local/state/devdesk} (XDG's default). */
public final class StateFolder {

    private StateFolder() {}

    /** DevDesk's state folder for this environment. Only builds the path; the folder may not exist yet. */
    public static Path of(Map<String, String> env) {
        String state = env.getOrDefault("XDG_STATE_HOME", "");
        Path base = state.isBlank() ? Path.of(env.getOrDefault("HOME", "/tmp"), ".local", "state") : Path.of(state);
        return base.resolve("devdesk");
    }
}
