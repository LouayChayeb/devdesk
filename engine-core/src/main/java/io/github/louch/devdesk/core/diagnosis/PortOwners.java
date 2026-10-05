package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Says who holds a port, as precisely as the machine allows. Shared by the port-conflict rule and the supervisor's
 * pre-start check, so both give the same explanation.
 */
public final class PortOwners {

    /** The sentence part and the fix. */
    public record Explanation(String who, String fix, boolean insideProject) {}

    private PortOwners() {}

    /**
     * "your own `npm run dev` (PID 4121), running in this project's folder backend/ for 2 h, started outside DevDesk"
     * when it lives in the project folder; otherwise "PID 4121 (node server.js), which is not part of this project".
     */
    public static Explanation explain(Listener l, Introspection system, ProjectSpec project, Instant now) {
        Optional<ProcessInfo> info = l.pid().flatMap(system::process);
        Optional<Path> folder = info.flatMap(ProcessInfo::workingDirectory)
                .filter(d -> d.startsWith(project.root().toAbsolutePath().normalize()));
        if (info.isPresent() && folder.isPresent()) {
            ProcessInfo p = info.get();
            String rel = project.root().relativize(folder.get()).toString();
            String age = p.startedAt()
                    .map(t -> " for " + age(Duration.between(t, now)))
                    .orElse("");
            return new Explanation(
                    "your own `" + p.command() + "` (PID " + p.pid() + "), running in this project's folder"
                            + (rel.isEmpty() ? "" : " " + rel + "/") + age
                            + ". DevDesk didn't start it: it was probably "
                            + "started by hand in another terminal",
                    "stop it in that terminal (Ctrl+C), or: kill " + p.pid(),
                    true);
        }
        return new Explanation(l.describe() + ", which is not part of this project", l.suggestion(), false);
    }

    /** "45 s", "12 min", "3 h", "2 days". */
    public static String age(Duration d) {
        long s = Math.max(0, d.toSeconds());
        if (s < 120) {
            return s + " s";
        }
        if (s < 7200) {
            return s / 60 + " min";
        }
        if (s < 172_800) {
            return s / 3600 + " h";
        }
        return s / 86_400 + " days";
    }
}
