package io.github.louch.devdesk.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Which images DevDesk downloaded, and for which project: {@code <image> TAB <project key>} per line. An image can't
 * carry a label once pulled, so this record is how "Remove" knows an image is DevDesk's to delete, and not one the user
 * had before.
 */
public final class PullLog {

    private final Optional<Path> file;

    /** @param file empty: nothing is recorded (tests, tools) */
    public PullLog(Optional<Path> file) {
        this.file = file;
    }

    /** {@code $XDG_STATE_HOME/devdesk/pulls.txt}, next to DevDesk's other state. */
    public static Path defaultFile(Map<String, String> env) {
        String state = env.getOrDefault("XDG_STATE_HOME", "");
        Path base = state.isBlank() ? Path.of(env.getOrDefault("HOME", "/tmp"), ".local", "state") : Path.of(state);
        return base.resolve("devdesk").resolve("pulls.txt");
    }

    public synchronized void record(String image, String projectKey) {
        if (file.isEmpty() || entries().contains(image + "\t" + projectKey)) {
            return;
        }
        try {
            Files.createDirectories(file.get().getParent());
            Files.writeString(
                    file.get(),
                    image + "\t" + projectKey + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Not recorded: Remove keeps the image, the safe side.
        }
    }

    /** The images DevDesk pulled for this project. */
    public synchronized List<String> pulledFor(String projectKey) {
        return entries().stream()
                .filter(l -> l.endsWith("\t" + projectKey))
                .map(l -> l.substring(0, l.lastIndexOf('\t')))
                .toList();
    }

    /** Whether another project of DevDesk's uses it too (then it stays). */
    synchronized boolean usedByAnother(String image, String projectKey) {
        return entries().stream().anyMatch(l -> l.startsWith(image + "\t") && !l.equals(image + "\t" + projectKey));
    }

    /** Once the image is deleted, no project's record of it means anything any more. */
    synchronized void forget(String image) {
        if (file.isEmpty() || !Files.exists(file.get())) {
            return;
        }
        try {
            Files.write(
                    file.get(),
                    entries().stream().filter(l -> !l.startsWith(image + "\t")).toList(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            // stays recorded: a later Remove finds the image gone and skips it
        }
    }

    private List<String> entries() {
        try {
            return file.isPresent() && Files.exists(file.get())
                    ? Files.readAllLines(file.get(), StandardCharsets.UTF_8)
                    : List.of();
        } catch (IOException e) {
            return List.of();
        }
    }
}
