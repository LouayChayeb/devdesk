package io.github.louch.devdesk.linux.resources;

import io.github.louch.devdesk.core.resources.ResourceSampler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

/** Adds up CPU time, memory and open sockets of processes from /proc/<pid>/stat, status and fd/. */
public final class ProcResources implements ResourceSampler {

    /** Kernel clock ticks per second (USER_HZ), checked in engine-linux's ClockTicksTest. */
    public static final int TICKS_PER_SECOND = 100;

    @Override
    public Usage sample(Set<Long> pids) {
        long ticks = 0;
        long bytes = 0;
        int sockets = 0;
        for (long pid : pids) {
            ticks += cpuTicks(pid);
            bytes += rssBytes(pid);
            sockets += sockets(pid);
        }
        return new Usage((double) ticks / TICKS_PER_SECOND, bytes, sockets);
    }

    /** Each entry of /proc/<pid>/fd is an open file descriptor; sockets link to "socket:[inode]". */
    private static int sockets(long pid) {
        try (Stream<Path> fds = Files.list(Path.of("/proc", Long.toString(pid), "fd"))) {
            return (int) fds.filter(fd -> {
                        try {
                            return Files.readSymbolicLink(fd).toString().startsWith("socket:[");
                        } catch (IOException e) {
                            return false; // closed while we looked
                        }
                    })
                    .count();
        } catch (IOException | RuntimeException e) {
            return 0; // gone
        }
    }

    private static long cpuTicks(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
            // Fields after the program name (which may contain spaces): utime is field 14, stime field 15.
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).trim().split(" ");
            return Long.parseLong(f[14 - 3]) + Long.parseLong(f[15 - 3]);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    private static long rssBytes(long pid) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc", Long.toString(pid), "status"))) {
                if (line.startsWith("VmRSS:")) { // e.g. "VmRSS:     41236 kB"
                    return Long.parseLong(line.replaceAll("\\D+", "")) * 1024;
                }
            }
        } catch (IOException | RuntimeException e) {
            // gone, or a kernel thread without memory
        }
        return 0;
    }
}
