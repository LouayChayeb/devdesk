package io.github.louch.devdesk.core.remote;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SshCommandsTest {

    private final RemoteTarget box = new RemoteTarget("louay", "box.local", 2222, "~/apps/shop");

    @Test
    void targetsAreCheckedBeforeTheyReachACommand() {
        assertThat(box.problem()).isEmpty();
        assertThat(new RemoteTarget("louay; rm -rf /", "h", 22, "~/x").problem())
                .isPresent();
        assertThat(new RemoteTarget("u", "h $(id)", 22, "~/x").problem()).isPresent();
        assertThat(new RemoteTarget("u", "h", 22, "~/x;id").problem()).isPresent();
        assertThat(new RemoteTarget("u", "h", 22, "~/../etc").problem()).isPresent();
        assertThat(new RemoteTarget("u", "h", 0, "~/x").problem()).isPresent();
    }

    @Test
    void sshUsesKeysOnlyAndTheRightPort() {
        List<String> argv = SshCommands.ssh(box, "echo hi");
        assertThat(argv)
                .startsWith("ssh", "-p", "2222")
                .contains("BatchMode=yes")
                .endsWith("louay@box.local", "echo hi");
    }

    @Test
    void aKeyFileAndDevDesksOwnKnownHostsAreUsedWhenGiven() {
        var t = new RemoteTarget("dev", "127.0.0.1", 2222, "~/apps/x", "/keys/test key", "")
                .withKnownHosts("/state/known_hosts");
        assertThat(t.problem()).isPresent(); // a space in the key path: refused
        var ok = new RemoteTarget("dev", "127.0.0.1", 2222, "~/apps/x", "/keys/test_key", "")
                .withKnownHosts("/state/known_hosts");
        List<String> argv = SshCommands.ssh(ok, "true");
        assertThat(argv).containsSequence("-i", "/keys/test_key", "-o", "IdentitiesOnly=yes");
        assertThat(argv).contains("UserKnownHostsFile=/state/known_hosts ~/.ssh/known_hosts");
        // rsync splits -e at spaces: the two-file option is kept together in quotes.
        String e = SshCommands.syncProject(ok, "/w/x")
                .get(SshCommands.syncProject(ok, "/w/x").indexOf("-e") + 1);
        assertThat(e).contains("\"UserKnownHostsFile=/state/known_hosts ~/.ssh/known_hosts\"");
    }

    @Test
    void syncCopiesTheFolderContentWithoutDependencies() {
        List<String> argv = SshCommands.syncProject(box, "/work/shop");
        assertThat(argv)
                .contains("--exclude=node_modules", "--exclude=.git", "/work/shop/", "louay@box.local:~/apps/shop/");
        assertThat(argv.get(argv.indexOf("-e") + 1)).startsWith("ssh -p 2222");
    }

    @Test
    void theTunnelForwardsOnlyToTheRemoteMachinesOwnLoopback() {
        List<String> argv = SshCommands.tunnel(box, List.of(new int[] {41000, 35000}, new int[] {3000, 3000}));
        assertThat(argv).contains("-N", "127.0.0.1:41000:127.0.0.1:35000", "127.0.0.1:3000:127.0.0.1:3000");
        assertThat(SshCommands.startEngine(box, true)).contains("--serve ~/apps/shop --trust");
    }
}
