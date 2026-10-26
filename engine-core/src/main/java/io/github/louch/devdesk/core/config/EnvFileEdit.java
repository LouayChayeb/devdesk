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

    /**
     * {@code key=value} replaces the last line that sets {@code key}, or is added at the end. The last one, because
     * that's the one readers use when a key is set twice: changing an earlier one would change nothing.
     */
    public static String set(String content, String key, String value) {
        requireKey(key);
        List<String> lines = new ArrayList<>(content.lines().toList());
        String line = key + "=" + quote(value);
        boolean replaced = false;
        for (int i = lines.size() - 1; i >= 0 && !replaced; i--) {
            if (sets(lines.get(i), key)) {
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
        if (value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("a .env value can't contain a line break");
        }
        // strip(): the reader trims more than ASCII spaces (e.g. U+2003, U+001F), so such ends need quotes too.
        return value.matches("[^\\s\"'#$\\\\]*") && value.strip().equals(value)
                ? value
                : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
