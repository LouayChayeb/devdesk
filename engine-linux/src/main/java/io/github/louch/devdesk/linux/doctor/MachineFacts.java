package io.github.louch.devdesk.linux.doctor;

import io.github.louch.devdesk.core.doctor.Doctor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Gathers what the doctor needs from this machine. Docker is passed in: this module doesn't talk to Docker itself. */
public final class MachineFacts {

    /** Bigger files aren't version or build files. */
    private static final long MAX_FILE = 1024 * 1024;

    private MachineFacts() {}

    /**
     * @param root the project folder (its file system's free space is checked)
     * @param environment the variables services start with
     * @param dockerUnavailable why Docker can't be used, or empty
     * @param localImages the images Docker has, or empty when it can't be asked
     */
    public static Doctor.Facts gather(
            Path root,
            Map<String, String> environment,
            Supplier<Optional<String>> dockerUnavailable,
            Supplier<Optional<Set<String>>> localImages) {
        ToolVersions tools = new ToolVersions(environment);
        Optional<String> docker = dockerUnavailable.get();
        return new Doctor.Facts(
                MachineFacts::read,
                Files::exists,
                tools::of,
                docker,
                docker.isPresent() ? Optional.empty() : localImages.get(),
                freeSpace(root));
    }

    private static Optional<String> read(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_FILE) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(file));
        } catch (IOException e) {
            return Optional.empty(); // unreadable or binary: not a file the doctor can use
        }
    }

    /** The space a non-root user may still write (statvfs's f_bavail), where the project lives. */
    private static long freeSpace(Path root) {
        try {
            return Files.getFileStore(root).getUsableSpace();
        } catch (IOException e) {
            return Long.MAX_VALUE; // can't tell: say nothing rather than a false alarm
        }
    }
}
