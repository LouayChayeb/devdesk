package io.github.louch.devdesk.linux.project;

import io.github.louch.devdesk.core.project.ProjectSpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One DevDesk per project at a time, using a lock on a small file.
 *
 * <p>The lock is a kernel "record lock" (fcntl). The kernel drops it the moment the holding process ends, however it
 * ends, so a free lock proves no DevDesk runs the project, and leftovers found then are survivors of a dead one.
 */
public final class ProjectLock implements AutoCloseable {

    /** The outcome of trying to lock. */
    public sealed interface Result {
        /** This process now owns the project. Keep it open while running; close it when done. */
        record Acquired(ProjectLock lock) implements Result {}

        /** Another DevDesk runs the project; {@code owner} says which ("PID 4121, desktop app, since ..."). */
        record Busy(String owner) implements Result {

            /** 4121 for "PID 4121, ...": the owner's process, so `devdesk ps` doesn't mistake it for a ghost. */
            public Optional<Long> pid() {
                Matcher m = Pattern.compile("^PID (\\d+),").matcher(owner);
                return m.find() ? Optional.of(Long.parseLong(m.group(1))) : Optional.empty();
            }
        }
    }

    private final FileChannel channel;
    private final FileLock lock;

    private ProjectLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /** {@code $XDG_RUNTIME_DIR/devdesk/<project key>.lock} (a per-user RAM folder that's emptied at reboot). */
    public static Result acquire(Map<String, String> env, ProjectSpec project, String how) {
        String runtime = env.getOrDefault("XDG_RUNTIME_DIR", "");
        Path dir = runtime.isBlank()
                ? Path.of(env.getOrDefault("HOME", "/tmp"), ".local", "state", "devdesk")
                : Path.of(runtime, "devdesk");
        Path file = dir.resolve(project.key() + ".lock");
        try {
            Files.createDirectories(dir);
            FileChannel channel = FileChannel.open(
                    file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                lock = null; // this same JVM already holds it
            }
            if (lock == null) {
                String owner = Files.readString(file).strip();
                channel.close();
                return new Result.Busy(owner.isEmpty() ? "another DevDesk" : owner);
            }
            // Who owns it, for the message another DevDesk will show. Written after locking, so it's never stale.
            channel.truncate(0);
            channel.write(ByteBuffer.wrap(("PID " + ProcessHandle.current().pid() + ", " + how + ", since "
                            + Instant.now().toString().substring(0, 19).replace('T', ' ') + " UTC")
                    .getBytes(StandardCharsets.UTF_8)));
            channel.force(true);
            return new Result.Acquired(new ProjectLock(channel, lock));
        } catch (IOException e) {
            // Can't lock (read-only home?): don't block the user, but don't pretend either.
            System.err.println(
                    "devdesk: could not lock the project (" + e.getMessage() + "); not checking for another DevDesk");
            return new Result.Acquired(new ProjectLock(null, null)); // a lock that guards nothing
        }
    }

    @Override
    public void close() {
        if (lock == null) {
            return;
        }
        try {
            lock.release();
            channel.close();
        } catch (IOException e) {
            // the process is ending anyway; the kernel drops the lock with it
        }
    }
}
