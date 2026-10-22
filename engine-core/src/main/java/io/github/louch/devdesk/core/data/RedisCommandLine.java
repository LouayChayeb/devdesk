package io.github.louch.devdesk.core.data;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** The Workbench's Redis command box: splits a typed line into words like redis-cli, and spots risky commands. */
public final class RedisCommandLine {

    /** Commands that wipe or stop a whole server: the app asks before sending them. */
    private static final Set<String> RISKY =
            Set.of("FLUSHALL", "FLUSHDB", "SHUTDOWN", "DEBUG", "FAILOVER", "REPLICAOF", "SLAVEOF");
    /** Commands that turn the connection into an endless stream; a request/answer box can't show them. */
    private static final Set<String> STREAMING =
            Set.of("MONITOR", "SUBSCRIBE", "PSUBSCRIBE", "SSUBSCRIBE", "SYNC", "PSYNC");

    private RedisCommandLine() {}

    /**
     * {@code set greeting "hello world"} → [set, greeting, hello world]. Double quotes understand {@code \n}, {@code \"}
     * and {@code \x41}; single quotes take everything literally, as in redis-cli.
     *
     * @throws DataException when a quote isn't closed
     */
    public static List<String> split(String line) {
        List<String> words = new ArrayList<>();
        int i = 0;
        int n = line.length();
        while (true) {
            while (i < n && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i == n) {
                return words;
            }
            StringBuilder word = new StringBuilder();
            char quote = 0;
            while (i < n) {
                char c = line.charAt(i);
                if (quote == 0) {
                    if (Character.isWhitespace(c)) {
                        break;
                    }
                    if (c == '"' || c == '\'') {
                        quote = c;
                    } else {
                        word.append(c);
                    }
                } else if (c == quote) {
                    quote = 0;
                } else if (quote == '"' && c == '\\' && i + 1 < n) {
                    i = escape(line, i + 1, word);
                    continue;
                } else {
                    word.append(c);
                }
                i++;
            }
            if (quote != 0) {
                throw new DataException("the line has an unclosed " + quote + " quote");
            }
            words.add(word.toString());
        }
    }

    /** Reads the escape that starts at {@code i} (just after the backslash); returns where the rest begins. */
    private static int escape(String line, int i, StringBuilder word) {
        char c = line.charAt(i);
        if (c == 'x' && i + 2 < line.length() && isHex(line.charAt(i + 1)) && isHex(line.charAt(i + 2))) {
            word.append((char) HexFormat.fromHexDigits(line, i + 1, i + 3));
            return i + 3;
        }
        word.append(
                switch (c) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case 'b' -> '\b';
                    case 'a' -> (char) 7;
                    default -> c; // \" and \\ are themselves
                });
        return i + 1;
    }

    private static boolean isHex(char c) {
        return Character.digit(c, 16) >= 0;
    }

    /** Why this command should be confirmed first, if it should. */
    public static Optional<String> risk(List<String> words) {
        if (words.isEmpty()) {
            return Optional.empty();
        }
        String name = words.getFirst().toUpperCase(Locale.ROOT);
        if (RISKY.contains(name)) {
            return Optional.of(name + " affects the whole Redis server, not one key");
        }
        if (name.equals("KEYS")) {
            return Optional.of("KEYS reads every key at once and blocks Redis meanwhile; the key list on the left walks"
                    + " them in steps (SCAN) instead");
        }
        if (name.equals("CONFIG") && words.size() > 1 && !words.get(1).equalsIgnoreCase("GET")) {
            return Optional.of("CONFIG " + words.get(1).toUpperCase(Locale.ROOT) + " changes the server's settings");
        }
        return Optional.empty();
    }

    /** Commands the box refuses, with what to use instead. */
    public static Optional<String> refused(List<String> words) {
        if (words.isEmpty()) {
            return Optional.of("type a command, like GET user:1");
        }
        String name = words.getFirst().toUpperCase(Locale.ROOT);
        if (STREAMING.contains(name)) {
            return Optional.of(name + " never ends: it turns the connection into a stream, which this box can't show."
                    + " Run it in redis-cli in a terminal");
        }
        if (name.equals("SELECT")) {
            return Optional.of("each command opens its own connection, so SELECT wouldn't last;"
                    + " the Workbench uses database 0");
        }
        return Optional.empty();
    }
}
