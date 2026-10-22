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
}
