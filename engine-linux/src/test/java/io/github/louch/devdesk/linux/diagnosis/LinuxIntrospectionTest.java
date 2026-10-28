package io.github.louch.devdesk.linux.diagnosis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.diagnosis.Introspection.FileStatus;
import io.github.louch.devdesk.linux.network.ProcNetListeners;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LinuxIntrospectionTest {

    private final LinuxIntrospection system = new LinuxIntrospection(new ProcNetListeners());

    @Test
    void findsProgramsLikeTheShellDoes(@TempDir Path dir) throws Exception {
        Path tool = Files.writeString(dir.resolve("mytool"), "#!/bin/sh\n");
        tool.toFile().setExecutable(true);
        Files.writeString(dir.resolve("notes"), "not a program");

        assertThat(system.findExecutable("sh", "/nonexistent:/usr/bin:/bin")).isPresent();
        assertThat(system.findExecutable("mytool", "/usr/bin:" + dir)).contains(tool);
        assertThat(system.findExecutable("notes", dir.toString())).isEmpty();
        assertThat(system.fileStatus(dir.resolve("notes"))).isEqualTo(FileStatus.NOT_EXECUTABLE);
        assertThat(system.fileStatus(dir.resolve("nope"))).isEqualTo(FileStatus.MISSING);
    }

    @Test
    void listsThePortsAProcessListensOn() throws Exception {
        try (ServerSocket a = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                ServerSocket b = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            var ports = system.listenersOf(Set.of(ProcessHandle.current().pid())).stream()
                    .map(l -> l.port())
                    .toList();

            assertThat(ports).contains(a.getLocalPort(), b.getLocalPort());
        }
    }

    @Test
    void devdeskScopeNameGivesTheProjectKey() {
        String cgroup = "0::/user.slice/user-1000.slice/user@1000.service/app.slice/"
                + "devdesk-three-tier-70fadd7e--db-0b67f03f.scope\n";

        assertThat(LinuxIntrospection.projectKeyIn(cgroup)).contains("three-tier-70fadd7e");
        assertThat(LinuxIntrospection.projectKeyIn("0::/init.scope\n")).isEmpty();
    }

    @Test
    void allListenersIncludesOurOwnSocketWithItsPid() throws Exception {
        try (var server = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            assertThat(system.allListeners()).anySatisfy(l -> {
                assertThat(l.port()).isEqualTo(server.getLocalPort());
                assertThat(l.pid()).contains(ProcessHandle.current().pid());
            });
        }
    }

    @Test
    void describesALiveProcessWithItsCommandFolderAndParent(@TempDir Path dir) throws Exception {
        Process p = new ProcessBuilder("sleep", "120").directory(dir.toFile()).start();
        try {
            var info = system.process(p.pid()).orElseThrow();

            assertThat(info.pid()).isEqualTo(p.pid());
            assertThat(info.parentPid()).isEqualTo(ProcessHandle.current().pid());
            assertThat(info.command()).isEqualTo("sleep 120"); // NUL-separated arguments joined with spaces
            assertThat(info.workingDirectory()).contains(dir.toRealPath());
            assertThat(info.startedAt()).isPresent();

            // Finding who works in a project's folder ("what's still running in ~/shop?").
            assertThat(system.processesIn(dir)).extracting(i -> i.pid()).containsExactly(p.pid());
            assertThat(system.processesIn(dir.resolve("elsewhere"))).isEmpty();
        } finally {
            p.destroyForcibly().waitFor();
        }
    }

    @Test
    void goneAndZombieProcessesAreNotReported() throws Exception {
        Process gone = new ProcessBuilder("true").start();
        gone.onExit().get(); // exited and collected
        assertThat(system.process(gone.pid())).isEmpty();
        assertThat(system.process(Long.MAX_VALUE)).isEmpty();

        // The child exits after its parent became `sleep` (exec), which never collects it: a zombie until sleep ends.
        // (A child exiting at once could be collected by sh itself before the exec: CI saw that, and no zombie.)
        Process parent = new ProcessBuilder("sh", "-c", "sleep 0.3 & echo $!; exec sleep 120").start();
        try {
            long zombie = Long.parseLong(
                    new java.io.BufferedReader(new java.io.InputStreamReader(parent.getInputStream())).readLine());
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!io.github.louch.devdesk.linux.process.ProcStat.read(zombie)
                            .map(io.github.louch.devdesk.linux.process.ProcStat::isZombie)
                            .orElse(false)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(io.github.louch.devdesk.linux.process.ProcStat.read(zombie))
                    .hasValueSatisfying(st -> assertThat(st.isZombie()).isTrue());
            assertThat(system.process(zombie)).isEmpty();
            assertThat(system.process(parent.pid())).isPresent();
        } finally {
            parent.destroyForcibly().waitFor();
        }
    }

    @Test
    void readFileReadsOnlySmallRegularFiles(@TempDir Path dir) throws Exception {
        Path small = Files.writeString(dir.resolve(".env.example"), "PORT=3000\n");
        Path exact = Files.write(dir.resolve("exact"), new byte[64 * 1024]);
        Path big = Files.write(dir.resolve("big"), new byte[64 * 1024 + 1]);

        assertThat(system.readFile(small)).contains("PORT=3000\n");
        assertThat(system.readFile(exact)).isPresent();
        assertThat(system.readFile(big)).isEmpty();
        assertThat(system.readFile(dir)).isEmpty(); // a folder
        assertThat(system.readFile(dir.resolve("missing"))).isEmpty();
        Path tool = Files.writeString(dir.resolve("tool"), "#!/bin/sh\n");
        tool.toFile().setExecutable(true);
        assertThat(system.fileStatus(tool)).isEqualTo(FileStatus.EXECUTABLE);
        assertThat(system.findExecutable("tool", "::" + dir)).contains(tool); // empty PATH entries are skipped
    }
}
