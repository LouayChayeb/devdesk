package io.github.louch.devdesk.core.watch;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides when a watched service restarts: after its files changed and then stayed quiet for a moment. Editors save
 * in bursts (a "save all", a formatter, a git checkout): one restart after the burst, not one per file. Pure: the
 * adapter looks at the files, this only compares what it saw.
 */
public final class RestartOnChange {

    /** One restart to do, with the files that caused it (so the logs can say why). */
    public record Restart(String service, List<Path> changed) {}

    private final Clock clock;
    private final Duration quiet;
    /** Per service: each file's last-modified time, as last seen. */
    private final Map<String, Map<Path, Long>> seen = new HashMap<>();
    /** Per service: files changed since the last restart, and when the last one changed. */
    private final Map<String, Set<Path>> pending = new HashMap<>();

    private final Map<String, Instant> lastChange = new HashMap<>();

    /** @param quiet how long the files must stay unchanged before the restart */
    public RestartOnChange(Clock clock, Duration quiet) {
        this.clock = clock;
        this.quiet = quiet;
    }

    /**
     * Compares a new look at each watched service's files with the previous one. The first look of a service is the
     * starting point: nothing "changed" yet.
     *
     * @param now per service: file → last-modified time (millis)
     * @return the services to restart now
     */
    public List<Restart> tick(Map<String, Map<Path, Long>> now) {
        Instant at = clock.instant();
        seen.keySet().retainAll(now.keySet()); // a service no longer watched starts fresh next time
        pending.keySet().retainAll(now.keySet());
        lastChange.keySet().retainAll(now.keySet());
        now.forEach((service, files) -> {
            Map<Path, Long> before = seen.put(service, Map.copyOf(files));
            if (before == null) {
                return;
            }
            Set<Path> changed = new LinkedHashSet<>();
            files.forEach((file, modified) -> {
                if (!modified.equals(before.get(file))) {
                    changed.add(file); // new or changed
                }
            });
            before.keySet().stream().filter(f -> !files.containsKey(f)).forEach(changed::add); // deleted
            if (!changed.isEmpty()) {
                pending.computeIfAbsent(service, s -> new LinkedHashSet<>()).addAll(changed);
                lastChange.put(service, at);
            }
        });
        List<Restart> due = new ArrayList<>();
        for (Map.Entry<String, Instant> e : new LinkedHashMap<>(lastChange).entrySet()) {
            if (!Duration.between(e.getValue(), at).minus(quiet).isNegative()) {
                due.add(new Restart(e.getKey(), List.copyOf(pending.remove(e.getKey()))));
                lastChange.remove(e.getKey());
            }
        }
        return due;
    }
}
