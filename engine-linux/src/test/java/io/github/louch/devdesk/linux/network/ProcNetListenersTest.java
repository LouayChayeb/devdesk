package io.github.louch.devdesk.linux.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcNetListenersTest {

    @Test
    void addressesAreDecodedFromLittleEndianHex() {
        assertThat(ProcNetListeners.address("0100007F")).isEqualTo("127.0.0.1");
        assertThat(ProcNetListeners.address("00000000")).isEqualTo("0.0.0.0");
        assertThat(ProcNetListeners.address("0000000000000000FFFF00000100007F")).isEqualTo("::ffff:127.0.0.1");
    }

    @Test
    void socketsAreReadByState(@TempDir Path proc) throws Exception {
        Files.createDirectories(proc.resolve("net"));
        // Real lines from this machine: one LISTEN (0A) on 127.0.0.1:3001, one ESTABLISHED (01) connection.
        Files.writeString(proc.resolve("net/tcp"), """
                  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
                   0: 0100007F:0BB9 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 81234 1 0 100 0 0 10 0
                   1: 0100007F:0BB9 0100007F:D2F0 01 00000000:00000000 00:00000000 00000000  1000        0 81240 1 0 20 4 30 10 -1
                """);

        var listening = new ProcNetListeners(proc).read(proc.resolve("net/tcp"), "0A");
        var established = new ProcNetListeners(proc).read(proc.resolve("net/tcp"), "01");

        assertThat(listening)
                .containsExactly(new ProcNetListeners.Socket("127.0.0.1", 3001, "0.0.0.0", 0, 1000, 81234));
        assertThat(established) // 0xD2F0 = 54000, the client's random port
                .containsExactly(new ProcNetListeners.Socket("127.0.0.1", 3001, "127.0.0.1", 54000, 1000, 81240));
    }

    @Test
    void findsTheRealProcessHoldingAPort() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            var listener = new ProcNetListeners().listenerOn(server.getLocalPort());

            assertThat(listener).hasValueSatisfying(l -> {
                assertThat(l.pid()).contains(ProcessHandle.current().pid()); // this test's own JVM
                assertThat(l.command()).hasValueSatisfying(c -> assertThat(c).contains("java"));
                assertThat(l.describe())
                        .startsWith("PID " + ProcessHandle.current().pid() + " (");
            });
        }
    }

    @Test
    void freePortHasNoListener() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        assertThat(new ProcNetListeners().listenerOn(port)).isEmpty();
    }

    @Test
    void connectionsOfOwnProcessShowBothEnds() throws Exception {
        long me = ProcessHandle.current().pid();
        try (ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
                java.net.Socket client = new java.net.Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
                java.net.Socket accepted = server.accept()) {
            var connections = new ProcNetListeners().connectionsOf(Set.of(me));

            // The client's side goes *to* the server port; the accepted side comes *from* the client's port.
            assertThat(connections)
                    .anySatisfy(c -> assertThat(c.remotePort()).isEqualTo(server.getLocalPort()))
                    .anySatisfy(c -> {
                        assertThat(c.localPort()).isEqualTo(server.getLocalPort());
                        assertThat(c.remotePort()).isEqualTo(client.getLocalPort());
                    })
                    .allSatisfy(c -> assertThat(c.pid()).isEqualTo(me));
            assertThat(accepted.isConnected()).isTrue();
        }
    }
}
