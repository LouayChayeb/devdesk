package io.github.louch.devdesk.daemon;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * The extras switch. Extras are the tools beyond DevDesk's core (run, clean up, explain): mock server, traffic
 * recorder, stress lab, Kafka / SQS / S3 / Elasticsearch / RabbitMQ pages, running on another machine, and auto-sleep.
 * They're off unless turned on; while off, their API routes aren't even registered and auto-sleep never runs.
 *
 * <p>Turned on by {@code DEVDESK_EXTRAS=on} (wins when set), or by {@code extras=on} in
 * {@code $XDG_CONFIG_HOME/devdesk/settings.properties} (what the app's setting writes). Read once at start: a change
 * applies after a restart.
 */
final class Extras {

    static final String ENV = "DEVDESK_EXTRAS";
    static final String KEY = "extras";

    private Extras() {}

    /** Is the switch on? An env var that isn't clearly on or off is ignored, so the setting decides. */
    static boolean enabled(Map<String, String> env) {
        return parse(env.get(ENV)).or(() -> saved(env)).orElse(false);
    }

    /** What the settings file says, ignoring the env var: what the app's toggle shows and changes. */
    static Optional<Boolean> saved(Map<String, String> env) {
        Path f = file(env);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty(); // unreadable: as if never set
        }
        return parse(p.getProperty(KEY));
    }

    /** True when the env var is set to on or off: then the app's toggle can't change anything. */
    static boolean forcedByEnvironment(Map<String, String> env) {
        return parse(env.get(ENV)).isPresent();
    }

    /** Writes the setting, keeping any other keys in the file. */
    static void save(Map<String, String> env, boolean on) throws IOException {
        Path f = file(env);
        Files.createDirectories(f.getParent());
        Properties p = new Properties();
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        p.setProperty(KEY, on ? "on" : "off");
        // Written next to it, then renamed: rename(2) is atomic, so a crash never leaves half a file.
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            p.store(w, "DevDesk settings");
        }
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** {@code $XDG_CONFIG_HOME/devdesk/settings.properties}, or {@code ~/.config/...} (XDG's default). */
    static Path file(Map<String, String> env) {
        String config = env.getOrDefault("XDG_CONFIG_HOME", "");
        Path base = config.isBlank() ? Path.of(env.getOrDefault("HOME", "/tmp"), ".config") : Path.of(config);
        return base.resolve("devdesk").resolve("settings.properties");
    }

    private static Optional<Boolean> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "on", "true", "1", "yes" -> Optional.of(true);
            case "off", "false", "0", "no" -> Optional.of(false);
            default -> Optional.empty();
        };
    }
}
