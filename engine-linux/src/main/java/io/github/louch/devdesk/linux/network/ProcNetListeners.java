package io.github.louch.devdesk.linux.network;

import io.github.louch.devdesk.core.network.Connection;
import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.network.ListenerLookup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds listening TCP sockets and open connections in {@code /proc/net/tcp} and {@code /proc/net/tcp6}, then the process holding each one
 * by scanning {@code /proc/<pid>/fd/}. Needs no root: other users' sockets are reported by their uid only.
 */
public final class ProcNetListeners implements ListenerLookup {

    // Socket state numbers from the kernel's include/net/tcp_states.h.
    private static final String TCP_ESTABLISHED = "01";
    private static final String TCP_LISTEN = "0A";

    private final Path proc;

    public ProcNetListeners() {
        this(Path.of("/proc"));
    }

    /** {@code proc} is replaceable so tests can use fixture files. */
    ProcNetListeners(Path proc) {
        this.proc = proc;
    }

    @Override
    public Optional<Listener> listenerOn(int port) {
        return tables(TCP_LISTEN).stream()
                .filter(s -> s.port() == port)
                .findFirst()
                .map(s -> {
                    Optional<Long> pid = ownerOf(s.inode());
                    return new Listener(port, s.address(), s.uid(), pid, pid.flatMap(this::commandLine));
                });
    }

    /**
     * Every TCP port the given processes listen on. Scans only those processes' file descriptors, so it stays cheap
     * however busy the machine is.
     */
    public List<Listener> listenersOf(Set<Long> pids) {
        if (pids.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> ownerOfInode = ownersOfSockets(pids);
        return tables(TCP_LISTEN).stream()
                .filter(s -> ownerOfInode.containsKey(s.inode()))
                .map(s -> {
                    long pid = ownerOfInode.get(s.inode());
                    return new Listener(s.port(), s.address(), s.uid(), Optional.of(pid), commandLine(pid));
                })
                .toList();
    }

    /**
     * Every listening TCP socket on the machine. Scans each process's fd/ once (not once per socket), so a busy
     * machine costs one pass over /proc.
     */
    public List<Listener> allListeners() {
        Map<Long, Long> ownerOfInode = new HashMap<>();
        try (Stream<Path> all = Files.list(proc)) {
            all.filter(p -> p.getFileName().toString().chars().allMatch(Character::isDigit))
                    .map(p -> Long.parseLong(p.getFileName().toString()))
                    .forEach(pid -> socketInodes(pid).forEach(inode -> ownerOfInode.put(inode, pid)));
        } catch (IOException e) {
            return List.of();
        }
        return tables(TCP_LISTEN).stream()
                .map(s -> {
                    Optional<Long> pid = Optional.ofNullable(ownerOfInode.get(s.inode()));
                    return new Listener(s.port(), s.address(), s.uid(), pid, pid.flatMap(this::commandLine));
                })
                .toList();
    }

    /** Every open TCP connection the given processes hold, in both directions. Same cheap scan as above. */
    public List<Connection> connectionsOf(Set<Long> pids) {
        if (pids.isEmpty()) {
            return List.of();
        }
        Map<Long, Long> ownerOfInode = ownersOfSockets(pids);
        return tables(TCP_ESTABLISHED).stream()
                .filter(s -> ownerOfInode.containsKey(s.inode()))
                .map(s -> new Connection(
                        ownerOfInode.get(s.inode()), s.address(), s.port(), s.remoteAddress(), s.remotePort()))
                .toList();
    }

    /** socket inode → PID, for the sockets these processes have open. */
    private Map<Long, Long> ownersOfSockets(Set<Long> pids) {
        Map<Long, Long> ownerOfInode = new HashMap<>();
        for (long pid : pids) {
            socketInodes(pid).forEach(inode -> ownerOfInode.put(inode, pid));
        }
        return ownerOfInode;
    }

    private List<Socket> tables(String state) {
        List<Socket> sockets = new ArrayList<>(read(proc.resolve("net/tcp"), state));
        sockets.addAll(read(proc.resolve("net/tcp6"), state));
        return sockets;
    }

    private List<Long> socketInodes(long pid) {
        try (Stream<Path> fds = Files.list(proc.resolve(pid + "/fd"))) {
            return fds.map(fd -> {
                        try {
                            return Files.readSymbolicLink(fd).toString();
                        } catch (IOException e) {
                            return "";
                        }
                    })
                    .filter(link -> link.startsWith("socket:["))
                    .map(link -> Long.parseLong(link.substring(8, link.length() - 1)))
                    .toList();
        } catch (IOException e) {
            return List.of(); // exited, or not ours
        }
    }

    /**
     * One line of /proc/net/tcp: its own end, the other end (zeros for a listener), who owns it, and its inode (the
     * kernel's socket id).
     */
    record Socket(String address, int port, String remoteAddress, int remotePort, int uid, long inode) {}

    /** The sockets of {@code table} in one state, like {@link #TCP_LISTEN}. */
    List<Socket> read(Path table, String state) {
        List<Socket> result = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(table);
        } catch (IOException e) {
            return result; // no IPv6 on this machine, for example
        }
        for (String line : lines.subList(Math.min(1, lines.size()), lines.size())) { // first line is the header
            // sl local_address rem_address st tx:rx tr:when retrnsmt uid timeout inode ...
            String[] f = line.strip().split("\\s+");
            if (f.length > 9 && f[3].equals(state)) {
                String[] local = f[1].split(":");
                String[] remote = f[2].split(":");
                result.add(new Socket(
                        address(local[0]),
                        Integer.parseInt(local[1], 16),
                        address(remote[0]),
                        Integer.parseInt(remote[1], 16),
                        Integer.parseInt(f[7]),
                        Long.parseLong(f[9])));
            }
        }
        return result;
    }

    /**
     * The kernel prints addresses as hex in host byte order, so on x86/ARM (little-endian) each 4-byte group is
     * reversed: "0100007F" is 127.0.0.1.
     */
    static String address(String hex) {
        if (hex.length() == 8) {
            return Integer.parseInt(hex.substring(6, 8), 16) + "." + Integer.parseInt(hex.substring(4, 6), 16) + "."
                    + Integer.parseInt(hex.substring(2, 4), 16) + "." + Integer.parseInt(hex.substring(0, 2), 16);
        }
        if (hex.equals("0".repeat(32))) {
            return "::";
        }
        if (hex.startsWith("0000000000000000FFFF0000")) { // IPv4 mapped into IPv6: ::ffff:a.b.c.d
            return "::ffff:" + address(hex.substring(24));
        }
        return hex; // a real IPv6 address; shown raw, it's only used for display
    }

    /** The process with an open file descriptor pointing at "socket:[inode]". Only our own processes are readable. */
    private Optional<Long> ownerOf(long inode) {
        String target = "socket:[" + inode + "]";
        try (Stream<Path> pids = Files.list(proc)) {
            return pids.filter(p -> p.getFileName().toString().chars().allMatch(Character::isDigit))
                    .filter(p -> hasFd(p.resolve("fd"), target))
                    .map(p -> Long.parseLong(p.getFileName().toString()))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static boolean hasFd(Path fdDir, String target) {
        try (Stream<Path> fds = Files.list(fdDir)) {
            return fds.anyMatch(fd -> {
                try {
                    return Files.readSymbolicLink(fd).toString().equals(target);
                } catch (IOException e) {
                    return false; // closed meanwhile
                }
            });
        } catch (IOException e) {
            return false; // another user's process (permission denied), or it exited
        }
    }

    private Optional<String> commandLine(long pid) {
        try {
            // Arguments are NUL-separated in /proc/<pid>/cmdline.
            String cmd = Files.readString(proc.resolve(pid + "/cmdline"))
                    .replace('\0', ' ')
                    .strip();
            return cmd.isEmpty() ? Optional.empty() : Optional.of(cmd);
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
