package io.github.louch.devdesk.core.doctor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version requirements as projects write them: {@code 22}, {@code v22.3.0}, {@code 22.x}, {@code ^22.1},
 * {@code ~3.12}, {@code >=20}, {@code >=18 <23}, {@code 18 || 20}. Enough to say "Node 18 found, the project needs 22";
 * not a full npm semver implementation.
 */
public final class VersionSpec {

    private static final Pattern NUMBERS = Pattern.compile("(\\d+)(?:\\.(\\d+|x|\\*))?(?:\\.(\\d+|x|\\*))?");
    private static final Pattern PART = Pattern.compile("(>=|<=|>|<|\\^|~|=)?\\s*v?(\\d+(?:\\.(?:\\d+|x|\\*)){0,2})");

    private VersionSpec() {}

    /** The first version number in a tool's output: "v22.3.0" → [22, 3, 0]; "openjdk 25 2025-09-16" → [25]. */
    public static Optional<List<Integer>> parse(String text) {
        Matcher m = NUMBERS.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        List<Integer> v = new ArrayList<>();
        for (int g = 1; g <= 3; g++) {
            String part = m.group(g);
            if (part == null || part.equals("x") || part.equals("*")) {
                break;
            }
            v.add(Integer.parseInt(part));
        }
        return Optional.of(v);
    }

    /**
     * Whether {@code installed} meets {@code spec}. Empty when the spec isn't understood ("lts/*", "node"): then the
     * doctor says nothing rather than something wrong.
     */
    public static Optional<Boolean> satisfies(String spec, List<Integer> installed) {
        boolean any = false;
        for (String alternative : spec.split("\\|\\|")) {
            Optional<Boolean> ok = all(alternative.strip(), installed);
            if (ok.isEmpty()) {
                return Optional.empty();
            }
            any |= ok.get();
        }
        return Optional.of(any);
    }

    /** The version a person should install for {@code spec}, e.g. "22" for "^22.1", "20" for ">=20". */
    public static String suggestion(String spec) {
        Matcher m = PART.matcher(spec);
        return m.find() ? m.group(2).replaceAll("\\.(x|\\*)$", "") : spec;
    }

    private static Optional<Boolean> all(String alternative, List<Integer> installed) {
        Matcher m = PART.matcher(alternative);
        boolean found = false;
        int end = 0;
        while (m.find()) {
            if (!alternative.substring(end, m.start()).isBlank()
                    && !alternative.substring(end, m.start()).strip().equals(",")) {
                return Optional.empty(); // something between the parts we don't understand
            }
            found = true;
            end = m.end();
            List<Integer> wanted = parse(m.group(2)).orElseThrow();
            String op = m.group(1) == null ? "" : m.group(1);
            if (!holds(op, wanted, installed)) {
                return Optional.of(false);
            }
        }
        return found && alternative.substring(end).isBlank() ? Optional.of(true) : Optional.empty();
    }

    private static boolean holds(String op, List<Integer> wanted, List<Integer> installed) {
        int c = compare(installed, wanted);
        return switch (op) {
            case ">=" -> c >= 0;
            case ">" -> c > 0;
            case "<=" -> c <= 0;
            case "<" -> c < 0;
            // ^22.1: at least 22.1, still 22.x. ~3.12: at least 3.12, still 3.12.x.
            case "^" -> c >= 0 && installed.getFirst().equals(wanted.getFirst());
            case "~" -> c >= 0 && prefix(installed, wanted.subList(0, Math.min(2, wanted.size())));
            // "22", "22.x", "=3.12": the parts written must match, the rest may be anything.
            default -> prefix(installed, wanted);
        };
    }

    /** Compares only as many parts as {@code wanted} has: 22.3.1 vs 22 is equal. */
    private static int compare(List<Integer> installed, List<Integer> wanted) {
        for (int i = 0; i < wanted.size(); i++) {
            int have = i < installed.size() ? installed.get(i) : 0;
            if (have != wanted.get(i)) {
                return Integer.compare(have, wanted.get(i));
            }
        }
        return 0;
    }

    private static boolean prefix(List<Integer> installed, List<Integer> wanted) {
        return compare(installed, wanted) == 0;
    }
}
