package io.github.louch.devdesk.daemon;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * Which profile each project runs with on this machine ("demo", "test"). A personal choice, so it lives in DevDesk's
 * state folder, not in devdesk.yaml (which is shared through git).
 */
final class ActiveProfiles {

    private final Path file;

    ActiveProfiles(Path file) {
        this.file = file;
    }

    /** The project's profile, or empty for the default (no overrides). Unreadable means default, never an error. */
    synchronized Optional<String> of(String projectKey) {
        return Optional.ofNullable(load().getProperty(projectKey)).filter(p -> !p.isBlank());
    }

    /** Remembers {@code profile} for the project; empty goes back to the default. */
    synchronized void set(String projectKey, Optional<String> profile) throws IOException {
        Properties all = load();
        profile.ifPresentOrElse(p -> all.setProperty(projectKey, p), () -> all.remove(projectKey));
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), "profiles", ".tmp");
        try (Writer w = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
            all.store(w, "Active DevDesk profile per project (project key = profile name)");
        }
        // Moved into place in one step: a crash halfway never leaves a half-written file.
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Properties load() {
        Properties p = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException | IllegalArgumentException e) {
                // a broken file (bad bytes, a bad \\u escape) means "nothing remembered": the default is always safe
            }
        }
        return p;
    }
}
