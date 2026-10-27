package io.github.louch.devdesk.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marks every test container with the test JVM that made it, and removes the ones whose JVM is gone. A test JVM
 * killed mid-test (PIT does that to a hung test) never runs its @AfterEach, so without this its databases keep running
 * and pile up until the machine runs out of memory. The same idea as Testcontainers' "Ryuk" reaper.
 */
final class TestJvm {

    /** Label on containers a test creates itself (docker run, createContainerCmd). Its value is {@link #ID}. */
    static final String LABEL = "devdesk.test-jvm";

    /**
     * This JVM: PID + its start time in clock ticks since boot (/proc/self/stat field 22). The PID alone isn't enough:
     * the kernel reuses PIDs, so a new process with the same PID would make a dead JVM's containers look alive.
     */
    static final String ID = "j" + ProcessHandle.current().pid() + "s"
            + startTicks(ProcessHandle.current().pid()).orElseThrow();

    /** The whole test label value, or "j<pid>s<ticks>r<4 hex>" at the end of a project name. */
    private static final Pattern IN_LABEL = Pattern.compile("^j(\\d+)s(\\d+)$");

    private static final Pattern IN_PROJECT = Pattern.compile("j(\\d+)s(\\d+)r[0-9a-f]{4}$");

    static {
        // The last test's network: nothing runs reapOrphans after it.
        Runtime.getRuntime().addShutdownHook(new Thread(TestJvm::reapNetworks));
    }

    private TestJvm() {}

    /**
     * A unique tag for one test's project name, ending in this JVM's ID. Containers DevDesk itself creates carry the
     * project name in their devdesk.project label, so the reaper can find their owner without changing product code.
     */
    static String tag() {
        return ID + "r" + UUID.randomUUID().toString().substring(0, 4);
    }

    /**
     * Removes test containers whose JVM is dead. Only containers with the test label, or with a devdesk.project that
     * ends in a test tag, are looked at; anything else on the machine is never touched.
     */
    static void reapOrphans() {
        reap(LABEL, IN_LABEL);
        reap("devdesk.project", IN_PROJECT);
        reapNetworks();
    }

    /**
     * The networks DevDesk made for test projects of this JVM or a dead one. A test that removes its containers itself,
     * not through DevDesk's stop, leaves its project's network behind. Docker refuses to remove a network that a
     * container still uses, so a test that is still running keeps its own.
     */
    private static void reapNetworks() {
        String list = docker(
                        "network",
                        "ls",
                        "--filter",
                        "label=devdesk.project",
                        "--format",
                        "{{.ID}} {{.Label \"devdesk.project\"}}")
                .orElse("");
        for (String line : list.split("\n")) {
            String[] parts = line.strip().split(" ", 2);
            Matcher m = IN_PROJECT.matcher(parts.length == 2 ? parts[1] : "");
            if (m.find()) {
                long pid = Long.parseLong(m.group(1));
                long ticks = Long.parseLong(m.group(2));
                if (ID.equals("j" + pid + "s" + ticks) || !alive(pid, ticks)) {
                    docker("network", "rm", parts[0]);
                }
            }
        }
    }

    private static void reap(String label, Pattern owner) {
        String list = docker(
                        "ps", "-a", "--filter", "label=" + label, "--format", "{{.ID}} {{.Label \"" + label + "\"}}")
                .orElse("");
        for (String line : list.split("\n")) {
            String[] parts = line.strip().split(" ", 2);
            Matcher m = owner.matcher(parts.length == 2 ? parts[1] : "");
            if (m.find() && !alive(Long.parseLong(m.group(1)), Long.parseLong(m.group(2)))) {
                docker("rm", "-f", "-v", parts[0]); // -v: its anonymous volumes too
            }
        }
    }

    private static boolean alive(long pid, long ticks) {
        return startTicks(pid).map(t -> t == ticks).orElse(false);
    }

    /** /proc/<pid>/stat field 22, read after the last ')' because the command name in field 2 may contain spaces. */
    private static Optional<Long> startTicks(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
            String[] after = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            return Optional.of(Long.parseLong(after[19])); // field 3 is after[0], so field 22 is after[19]
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // no such process
        }
    }

    private static Optional<String> docker(String... args) {
        String[] command = new String[args.length + 1];
        command[0] = "docker";
        System.arraycopy(args, 0, command, 1, args.length);
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
