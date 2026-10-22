package io.github.louch.devdesk.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Changes one variable in a .env file's text and keeps everything else exactly as it was: comments, order, blank
 * lines. People read and edit these files by hand too, so DevDesk must not reformat them.
 */
public final class EnvFileEdit {

    /** Names an .env file can hold (the same as shells accept). */
    public static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private EnvFileEdit() {}

    /** {@code key=value} replaces the first line that sets {@code key}, or is added at the end. */
    public static String set(String content, String key, String value) {
        requireKey(key);
        List<String> lines = new ArrayList<>(content.lines().toList());
        String line = key + "=" + quote(value);
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            if (!replaced && sets(lines.get(i), key)) {
                // Keep "export " if the line had it: some people source their .env from the shell.
                lines.set(i, (lines.get(i).strip().startsWith("export ") ? "export " : "") + line);
                replaced = true;
            }
        }
        if (!replaced) {
            lines.add(line);
        }
        return String.join("\n", lines) + "\n";
    }

    /** Removes every line that sets {@code key}; comments above it stay (they may describe more than one line). */
    public static String remove(String content, String key) {
        requireKey(key);
        List<String> kept = content.lines().filter(l -> !sets(l, key)).toList();
        return kept.isEmpty() ? "" : String.join("\n", kept) + "\n";
    }

    private static boolean sets(String line, String key) {
        String l = line.strip();
        if (l.startsWith("export ")) {
            l = l.substring("export ".length()).strip();
        }
        int eq = l.indexOf('=');
        return eq > 0 && !l.startsWith("#") && l.substring(0, eq).strip().equals(key);
    }

    private static void requireKey(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("not a variable name: \"" + key + "\"");
        }
    }

    /** Quotes only when needed: spaces, # or quotes would change how .env readers see the value. */
    static String quote(String value) {
        if (value.contains("\n")) {
            throw new IllegalArgumentException("a .env value can't contain a line break");
        }
        return value.matches("[^\\s\"'#$\\\\]*")
                ? value
                : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
