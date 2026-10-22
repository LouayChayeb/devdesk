package io.github.louch.devdesk.core.changes;

import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything around a project that can break it without a code change, taken when it last started cleanly: its
 * devdesk.yaml, its env files (values only as fingerprints: they never leave this record), dependency lock files,
 * the exact images its containers ran, tool versions, and the git commit.
 *
 * @param envFiles per env file: each variable's fingerprint (a hash of its value), never the value
 * @param files per lock or build file (relative path): a fingerprint of its content
 * @param images per image as written in devdesk.yaml ("postgres:18"): the image id it was then
 * @param tools per tool ("node"): the version it printed
 */
public record KnownGood(
        Instant at,
        Optional<String> branch,
        Optional<String> commit,
        String devdeskYaml,
        Map<String, Map<String, String>> envFiles,
        Map<String, String> files,
        Map<String, String> images,
        Map<String, String> tools) {

    /**
     * One difference.
     *
     * @param kind what sort of change, so the verdict can match it with findings without reading text
     * @param subject what changed, machine-readable: the image reference, the file's relative path, the variable
     *     name, the tool, or the previous commit (for {@link Kind#CODE})
     * @param area "devdesk.yaml", ".env", "lock file", "image", "tool", "git"
     */
    public record Change(Kind kind, String subject, String area, String what, String detail) {}

    /** The sorts of change DevDesk can see. */
    public enum Kind {
        IMAGE,
        TOOL,
        CONFIG,
        ENV_FILE,
        ENV_ADDED,
        ENV_REMOVED,
        ENV_VALUE,
        LOCKFILE,
        BRANCH,
        CODE
    }

    /** What differs between then (when it worked) and now, most likely causes first. */
    public static List<Change> compare(KnownGood then, KnownGood now) {
        List<Change> out = new ArrayList<>();
        // Images first: "postgres:latest" quietly becoming a new major version is a classic.
        for (String image : union(then.images.keySet(), now.images.keySet())) {
            String a = then.images.get(image);
            String b = now.images.get(image);
            if (a != null && b != null && !a.equals(b)) {
                out.add(new Change(
                        Kind.IMAGE,
                        image,
                        "image",
                        image,
                        "now a different image (" + shortId(a) + " → " + shortId(b)
                                + "): it was pulled again or rebuilt since"));
            }
        }
        for (String tool : union(then.tools.keySet(), now.tools.keySet())) {
            String a = then.tools.getOrDefault(tool, "not found");
            String b = now.tools.getOrDefault(tool, "not found");
            if (!a.equals(b)) {
                out.add(new Change(Kind.TOOL, tool, "tool", tool, a + " → " + b));
            }
        }
        yamlChange(then.devdeskYaml, now.devdeskYaml).ifPresent(out::add);
        for (String file : union(then.envFiles.keySet(), now.envFiles.keySet())) {
            Map<String, String> a = then.envFiles.get(file);
            Map<String, String> b = now.envFiles.get(file);
            if (a == null) {
                out.add(new Change(Kind.ENV_FILE, file, ".env", file, "new file"));
                continue;
            }
            if (b == null) {
                out.add(new Change(Kind.ENV_FILE, file, ".env", file, "deleted"));
                continue;
            }
            for (String key : union(a.keySet(), b.keySet())) {
                if (!a.containsKey(key)) {
                    out.add(new Change(Kind.ENV_ADDED, key, ".env", file + ": " + key, "added"));
                } else if (!b.containsKey(key)) {
                    out.add(new Change(Kind.ENV_REMOVED, key, ".env", file + ": " + key, "removed"));
                } else if (!a.get(key).equals(b.get(key))) {
                    out.add(new Change(
                            Kind.ENV_VALUE, key, ".env", file + ": " + key, "value changed (values are never shown)"));
                }
            }
        }
        for (String file : union(then.files.keySet(), now.files.keySet())) {
            String a = then.files.get(file);
            String b = now.files.get(file);
            if (a == null) {
                out.add(new Change(Kind.LOCKFILE, file, "lock file", file, "new"));
            } else if (b == null) {
                out.add(new Change(Kind.LOCKFILE, file, "lock file", file, "deleted"));
            } else if (!a.equals(b)) {
                out.add(new Change(
                        Kind.LOCKFILE, file, "lock file", file, "changed: dependencies were added or upgraded"));
            }
        }
        if (!then.branch.equals(now.branch)) {
            out.add(new Change(
                    Kind.BRANCH,
                    now.branch.orElse(""),
                    "git",
                    "branch",
                    then.branch.orElse("(none)") + " → " + now.branch.orElse("(none)")));
        }
        if (then.commit.isPresent() && now.commit.isPresent() && !then.commit.equals(now.commit)) {
            out.add(new Change(
                    Kind.CODE,
                    then.commit.get(),
                    "git",
                    "commit",
                    shortId(then.commit.get()) + " → " + shortId(now.commit.get()) + ": code changed too (git log "
                            + shortId(then.commit.get()) + "..HEAD)"));
        }
        return out;
    }

    /**
     * devdesk.yaml lines that are gone or new, in file order. A line whose key looks secret is shown by its key only.
     */
    static List<Change> yaml(String then, String now) {
        List<String> a = lines(then);
        List<String> b = lines(now);
        List<Change> out = new ArrayList<>();
        Set<String> inB = new LinkedHashSet<>(b);
        Set<String> inA = new LinkedHashSet<>(a);
        for (String l : a) {
            if (!inB.contains(l)) {
                out.add(new Change(Kind.CONFIG, masked(l), "devdesk.yaml", "removed", masked(l)));
            }
        }
        for (String l : b) {
            if (!inA.contains(l)) {
                out.add(new Change(Kind.CONFIG, masked(l), "devdesk.yaml", "added", masked(l)));
            }
        }
        return out;
    }

    /** At most this many changed lines are spelled out in one devdesk.yaml change. */
    private static final int YAML_LINES_SHOWN = 6;

    /**
     * One edit of devdesk.yaml is one change, not one per line: "3 lines changed", with the lines (secrets masked) in
     * the detail. The subject holds every changed line, for the verdict to match with services and ports.
     */
    static Optional<Change> yamlChange(String then, String now) {
        List<Change> lines = yaml(then, now);
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        List<String> shown = lines.stream()
                .map(c ->
                        (c.what().equals("removed") ? "- " : "+ ") + c.detail().strip())
                .toList();
        String detail = String.join(" · ", shown.subList(0, Math.min(YAML_LINES_SHOWN, shown.size())))
                + (shown.size() > YAML_LINES_SHOWN ? " · … " + (shown.size() - YAML_LINES_SHOWN) + " more" : "");
        return Optional.of(new Change(
                Kind.CONFIG,
                String.join("\n", lines.stream().map(Change::detail).toList()),
                "devdesk.yaml",
                lines.size() == 1 ? "1 line changed" : lines.size() + " lines changed",
                detail));
    }

    private static List<String> lines(String text) {
        return text.lines()
                .map(String::stripTrailing)
                .filter(l -> !l.isBlank() && !l.strip().startsWith("#"))
                .toList();
    }

    /** "  DB_PASSWORD: s3cret" → "  DB_PASSWORD: (hidden)". */
    private static String masked(String line) {
        int colon = line.indexOf(':');
        if (colon > 0 && ServiceEnvironment.looksSecret(line.substring(0, colon).strip())) {
            return line.substring(0, colon + 1) + " (hidden)";
        }
        return line;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new TreeSet<>(a);
        all.addAll(b);
        return all;
    }

    private static String shortId(String id) {
        String s = id.replaceFirst("^sha256:", "");
        return s.length() > 12 ? s.substring(0, 12) : s;
    }
}
