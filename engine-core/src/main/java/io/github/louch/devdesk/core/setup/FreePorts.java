package io.github.louch.devdesk.core.setup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Moves a container's published port to the next free one when something else holds it (Jenkins on 8080, another
 * project's database on 5432): only the host side changes, so the app inside is untouched. The card says the new
 * address. A command's port is the app's own setting: it's named in a note, not changed behind its back.
 */
public final class FreePorts {

    private FreePorts() {}

    /** The same draft with taken container ports moved to free ones; {@code free} says whether a host port is free. */
    public static SetupDraft apply(SetupDraft draft, IntPredicate free) {
        Set<Integer> used = new HashSet<>();
        draft.services().forEach(s -> used.addAll(s.hostPorts()));
        // Two cards on one port collide the moment both start (excalidraw: its docs site and its app both on 3000).
        // A command's port is its own setting, so commands keep theirs and a container moves out of the way.
        Map<Integer, String> claimedBy = new HashMap<>();
        draft.services().stream()
                .filter(s -> !s.isContainer())
                .forEach(s -> s.hostPorts().forEach(p -> claimedBy.putIfAbsent(p, s.name())));
        List<SetupDraft.Service> services = new ArrayList<>();
        for (SetupDraft.Service s : draft.services()) {
            List<String> ports = new ArrayList<>();
            List<String> notes = new ArrayList<>(s.notes());
            SetupDraft.Health health = s.health();
            for (String p : s.ports()) {
                String[] parts = p.split(":");
                if (!s.isContainer()) {
                    ports.add(p);
                    if (p.matches("\\d{1,5}") && !free.test(Integer.parseInt(p))) {
                        notes.add("Port " + p + " is taken by another program: stop it, or set this app's port to a"
                                + " free one (often PORT in environment) before starting.");
                    }
                    String other = p.matches("\\d{1,5}") ? claimedBy.get(Integer.parseInt(p)) : null;
                    if (other != null && !other.equals(s.name())) {
                        notes.add("Port " + p + " is also " + other + "'s: both can't run at once. Set one of them to"
                                + " another port (often PORT in environment), or delete the card you don't need.");
                    }
                    continue;
                }
                if (parts.length < 2 || !parts[parts.length - 2].matches("\\d{1,5}") || p.endsWith("/udp")) {
                    ports.add(p);
                    continue;
                }
                int host = Integer.parseInt(parts[parts.length - 2]);
                String other = claimedBy.get(host);
                if (free.test(host) && other == null) {
                    claimedBy.put(host, s.name());
                    ports.add(p);
                    continue;
                }
                int next = host + 1;
                while (next < 65535 && (used.contains(next) || !free.test(next))) {
                    next++;
                }
                used.add(next);
                claimedBy.put(next, s.name());
                parts[parts.length - 2] = String.valueOf(next);
                ports.add(String.join(":", parts));
                notes.add("Port " + host + " is taken by "
                        + (other == null ? "another program" : other + " (another card)")
                        + ", so " + s.name() + " is published on " + next + " instead: open it at http://127.0.0.1:"
                        + next + "/");
                if (health.target().equals(String.valueOf(host))) {
                    health = new SetupDraft.Health(health.type(), String.valueOf(next), health.startPeriod());
                } else if (health.target().contains(":" + host + "/")
                        || health.target().endsWith(":" + host)) {
                    health = new SetupDraft.Health(
                            health.type(), health.target().replace(":" + host, ":" + next), health.startPeriod());
                }
            }
            services.add(new SetupDraft.Service(
                    s.name(),
                    s.kind(),
                    s.command(),
                    s.directory(),
                    s.image(),
                    ports,
                    s.volumes(),
                    s.environment(),
                    s.dependsOn(),
                    health,
                    s.restart(),
                    s.origin(),
                    notes,
                    s.extras()));
        }
        return new SetupDraft(draft.name(), draft.envFiles(), services, draft.notes(), draft.kept());
    }
}
