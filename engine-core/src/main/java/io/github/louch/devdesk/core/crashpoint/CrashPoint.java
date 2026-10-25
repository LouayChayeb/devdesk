package io.github.louch.devdesk.core.crashpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Named moments in the life of {@code up} and stop where DevDesk could die. Crash-point tests start DevDesk with the
 * JVM properties {@code devdesk.test.crashPoint=<name>} and {@code devdesk.test.crashPointFile=<path>}: when a thread
 * reaches that point, it writes the file and freezes there forever, and the test SIGKILLs the process. Then a new
 * DevDesk must find and stop everything the dead one left.
 *
 * <p>Only a JVM property turns it on (the launch scripts and the app never set one), so normal use can't reach it.
 * Without the property, {@link #reach} is one string comparison against an empty constant.
 */
public final class CrashPoint {

    /** A "Prove it" replica is running (only reached by {@code devdesk why --prove}, tested on its own). */
    public static final String PROOF_STARTED = "proof.started";

    /** Every point of {@code up} and stop, in the order a run reaches them. Tests iterate over this list. */
    public static final List<String> ALL = List.of(
            "up.locked", // the CLI holds the project lock, nothing cleaned or started yet
            "up.cleaned", // survivors of an earlier crash were stopped, nothing of this run started yet
            "launch.before", // about to start a service (process or container)
            "process.forked", // a native service's process exists; DevDesk hasn't confirmed its ownership yet
            "process.owned", // ownership confirmed, the supervisor doesn't know the PID yet
            "container.pulling", // a container's image is downloading
            "container.created", // the container exists but isn't started
            "container.started", // the container runs, the supervisor doesn't know it yet
            "health.probing", // a health check is in flight
            "up.all-up", // every service is up and the known-good snapshot is being saved
            "stop.begin", // Ctrl+C: the ordered stop is about to begin
            "stop.service", // one service is being stopped (the others still run)
            "stop.sigterm-sent", // SIGTERM sent to a native service's family, not yet confirmed gone
            "container.stop-sent", // docker stop done, the container not removed yet
            "stop.done"); // everything stopped, the lock not released yet

    private static final String POINT = System.getProperty("devdesk.test.crashPoint", "");
    private static final String FILE = System.getProperty("devdesk.test.crashPointFile", "");

    private CrashPoint() {}

    /** Freezes the calling thread forever if this is the point the test asked for; otherwise returns at once. */
    public static void reach(String point) {
        if (POINT.isEmpty() || !POINT.equals(point)) {
            return;
        }
        try {
            // Written next to it, then renamed: the test never reads a half-written file.
            Path file = Path.of(FILE);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, point + " " + ProcessHandle.current().pid() + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("crash point " + point + ": can't write " + FILE, e);
        }
        while (true) {
            LockSupport.park(); // the test SIGKILLs us here
        }
    }
}
