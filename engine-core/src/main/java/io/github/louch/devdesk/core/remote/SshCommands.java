package io.github.louch.devdesk.core.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * The commands DevDesk runs to work with a {@link RemoteTarget}: plain {@code ssh} and {@code rsync}, as the user
 * would type them, so each step can be shown and understood (and tried by hand when one fails).
 */
public final class SshCommands {

    /** Never copied: rebuilt or downloaded on the machine itself, or private to this one. */
    public static final List<String> EXCLUDES = List.of(
            ".git",
            "node_modules",
            ".venv",
            "venv",
            "__pycache__",
            "target",
            "build",
            "dist",
            ".gradle",
            ".idea",
            ".next",
            ".DS_Store");

    /** Where DevDesk keeps its own things on the remote machine. */
    public static final String HOME = "~/.devdesk";

    private SshCommands() {}

    /**
     * ssh with what makes it fit for a program: no password prompt (keys only: BatchMode), a time limit, and the
     * user's known hosts. A new host is accepted once ("accept-new"), a changed key is refused like SSH always does.
     */
    public static List<String> ssh(RemoteTarget t, String remoteCommand) {
        List<String> argv = new ArrayList<>(base(t));
        argv.add(t.login());
        argv.add(remoteCommand);
        return argv;
    }

    /** {@code rsync -az --delete} of the project folder, without what's in {@link #EXCLUDES}. */
    public static List<String> syncProject(RemoteTarget t, String localFolder) {
        List<String> argv = new ArrayList<>(List.of("rsync", "-az", "--delete"));
        EXCLUDES.forEach(e -> argv.add("--exclude=" + e));
        argv.add("-e");
        argv.add(shellJoin(base(t)));
        argv.add(localFolder.endsWith("/") ? localFolder : localFolder + "/"); // the folder's content, not itself
        argv.add(t.login() + ":" + t.folder() + "/");
        return argv;
    }

    /** Copies DevDesk's own engine (its installed folder) to {@link #HOME}/engine on the remote machine. */
    public static List<String> syncEngine(RemoteTarget t, String engineFolder) {
        List<String> argv = new ArrayList<>(List.of("rsync", "-az", "--delete", "-e", shellJoin(base(t))));
        argv.add(engineFolder.endsWith("/") ? engineFolder : engineFolder + "/");
        argv.add(t.login() + ":" + HOME + "/engine/");
        return argv;
    }

    /**
     * Starts the remote engine in server mode for the project, in the background (it stays up after SSH leaves),
     * writing where it listens to {@link #HOME}/server.json.
     *
     * @param trust the user clicked Deploy after seeing the commands: the remote engine may run them
     */
    public static String startEngine(RemoteTarget t, boolean trust) {
        return "mkdir -p " + HOME + " && (" + HOME + "/engine/bin/devdeskd --serve " + t.folder()
                + (trust ? " --trust" : "") + " > " + HOME + "/server.log 2>&1 < /dev/null &) && sleep 4 && cat "
                + HOME + "/server.json";
    }

    /** Stops the remote engine (it stops its services first, like closing the app does). */
    public static String stopEngine() {
        return "test -f " + HOME + "/server.json && kill $(sed -n 's/.*\"pid\":\\([0-9]*\\).*/\\1/p' " + HOME
                + "/server.json) && rm -f " + HOME + "/server.json || true";
    }

    /** What the remote machine has: Java, Docker, rsync. Printed as "name: version or missing" lines. */
    public static String check() {
        return "for c in java docker rsync; do printf '%s: ' $c; if command -v $c >/dev/null; then"
                + " ($c --version 2>&1 || $c -version 2>&1) | head -1; else echo missing; fi; done";
    }

    /**
     * A tunnel: {@code localPort} here reaches {@code remotePort} on the remote machine's own 127.0.0.1. Stays open
     * until the process is stopped ({@code -N}: no command, just forwarding).
     */
    public static List<String> tunnel(RemoteTarget t, List<int[]> localToRemote) {
        List<String> argv = new ArrayList<>(base(t));
        argv.add("-N");
        argv.add("-o");
        argv.add("ExitOnForwardFailure=yes"); // a port that can't be forwarded fails at once, with a message
        argv.add("-o");
        argv.add("ServerAliveInterval=15"); // notice a dead connection instead of hanging
        for (int[] p : localToRemote) {
            argv.add("-L");
            argv.add("127.0.0.1:" + p[0] + ":127.0.0.1:" + p[1]);
        }
        argv.add(t.login());
        return argv;
    }

    /** rsync splits its -e command at spaces, but keeps words in double quotes together. */
    static String shellJoin(List<String> words) {
        return String.join(
                " ",
                words.stream().map(w -> w.contains(" ") ? "\"" + w + "\"" : w).toList());
    }

    private static List<String> base(RemoteTarget t) {
        List<String> argv = new ArrayList<>(List.of(
                "ssh",
                "-p",
                Integer.toString(t.port()),
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=10",
                "-o",
                "StrictHostKeyChecking=accept-new"));
        if (!t.identityFile().isEmpty()) {
            argv.addAll(List.of("-i", t.identityFile(), "-o", "IdentitiesOnly=yes"));
        }
        if (!t.knownHosts().isEmpty()) {
            // DevDesk's own list first: new machines are written there. The user's list is still read.
            argv.addAll(List.of("-o", "UserKnownHostsFile=" + t.knownHosts() + " ~/.ssh/known_hosts"));
        }
        return argv;
    }
}
