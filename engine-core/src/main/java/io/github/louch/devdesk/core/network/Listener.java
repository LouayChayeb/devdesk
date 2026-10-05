package io.github.louch.devdesk.core.network;

import java.util.Optional;

/**
 * A program listening on a TCP port.
 *
 * @param pid and {@code command} are empty when the socket belongs to another user: without root, Linux only shows
 *     the owner's user ID
 */
public record Listener(int port, String address, int uid, Optional<Long> pid, Optional<String> command) {

    /** "PID 4121 (node server.js)", or "a process of user 0" when we may not see more. */
    public String describe() {
        return pid.map(p -> "PID " + p + command.map(c -> " (" + c + ")").orElse(""))
                .orElse("a process of another user (uid " + uid + ")");
    }

    /** What the user can do about it, as a command to copy. Never run automatically. */
    public String suggestion() {
        return pid.map(p -> "Free it with: kill " + p)
                .orElse("See who it is with: sudo ss -ltnp 'sport = :" + port + "'");
    }
}
