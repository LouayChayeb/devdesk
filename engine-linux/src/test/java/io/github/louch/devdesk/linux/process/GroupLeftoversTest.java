package io.github.louch.devdesk.linux.process;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery after a dead DevDesk, in group mode: records plus the DEVDESK_GROUP marker decide what is a leftover, and
 * removing one signals exactly those processes. The "dead DevDesk" is simulated: the test writes the record and
 * starts the family itself, as LinuxProcessRuntime would have.
 */
class GroupLeftoversTest {

    @TempDir
    Path dir;

    private final ProjectSpec project = new ProjectSpec("left", Path.of("/tmp/left-test"), Map.of(), Map.of());
    private final List<Process> started = new ArrayList<>();

    @AfterEach
    void stopWhatTheTestStarted() {
        // Only through the handles Java gave us, never by searching.
        started.forEach(p -> p.descendants().forEach(ProcessHandle::destroyForcibly));
        started.forEach(Process::destroyForcibly);
    }

    /** A process of ours: carries {@code marker} in its environment unless it's blank. */
    private Process spawn(String marker, String script) throws Exception {
        var pb = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
        pb.environment().remove(GroupLeftovers.MARKER);
        if (!marker.isBlank()) {
            pb.environment().put(GroupLeftovers.MARKER, marker);
        }
        Process p = pb.start();
        started.add(p);
        return p;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void aFamilyWithTheMarkerIsFoundAndStoppedButNothingElse() throws Exception {
        var groups = new GroupLeftovers(dir);
        Process before = spawn("m1", "sleep 120"); // carries the marker, but started before the record's time
        Thread.sleep(50);
        long notBefore = LinuxProcessRuntime.ticksSinceBoot();
        groups.remember(project.key(), "api", notBefore, "m1", "python3 api.py");
        Process family = spawn("m1", "sleep 120 & sleep 120 & wait");
        Process other = spawn("m2", "sleep 120"); // another family's marker
        Process plain = spawn("", "sleep 120"); // no marker at all
        waitFor(() -> GroupLeftovers.carriers("m1", notBefore).size() >= 3);

        List<Leftover> found = groups.find(project);

        assertThat(found).singleElement().satisfies(l -> {
            assertThat(l.service()).isEqualTo("api");
            assertThat(l.kind()).isEqualTo(Leftover.Kind.PROCESS);
            assertThat(l.id()).isEqualTo(project.key() + "/m1");
            assertThat(l.description()).isEqualTo("python3 api.py");
            assertThat(l.pids()).contains(family.pid()).hasSize(3);
            assertThat(l.pids()).doesNotContain(before.pid(), other.pid(), plain.pid());
            assertThat(l.since()).isPresent();
        });

        assertThat(groups.remove(found.getFirst())).isEqualTo("stopped");

        assertThat(family.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(GroupLeftovers.carriers("m1", notBefore)).isEmpty();
        assertThat(before.isAlive()).isTrue();
        assertThat(other.isAlive()).isTrue();
        assertThat(plain.isAlive()).isTrue();
        assertThat(dir.resolve(project.key()).resolve("m1.group")).doesNotExist(); // forgotten once gone
        assertThat(groups.find(project)).isEmpty();
    }

    @Test
    void aFamilyThatIgnoresSigtermIsForceKilledAfterTheGrace() throws Exception {
        var groups = new GroupLeftovers(dir);
        long notBefore = LinuxProcessRuntime.ticksSinceBoot();
        groups.remember(project.key(), "worker", notBefore, "stubborn", "worker");
        Process p = spawn(
                "stubborn",
                "trap '' TERM; while kill -0 " + ProcessHandle.current().pid() + " 2>/dev/null; do sleep 0.1; done");
        waitFor(() -> !GroupLeftovers.carriers("stubborn", notBefore).isEmpty());
        Thread.sleep(200); // the trap is set

        String result = groups.remove(groups.find(project).getFirst());

        assertThat(result).isEqualTo("force-killed: it ignored SIGTERM for 10 s");
        assertThat(p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isEqualTo(128 + 9); // SIGKILL, not a clean exit
        assertThat(dir.resolve(project.key()).resolve("stubborn.group")).doesNotExist();
    }

    @Test
    void aRecordWithNoLiveFamilyIsForgottenOnSight() throws Exception {
        var groups = new GroupLeftovers(dir);
        groups.remember(project.key(), "api", LinuxProcessRuntime.ticksSinceBoot(), "gone", "x");
        Path record = dir.resolve(project.key()).resolve("gone.group");
        assertThat(record).exists();

        assertThat(groups.find(project)).isEmpty();
        assertThat(record).doesNotExist();
    }

    @Test
    void aRecordFromAnotherBootClaimsNothing() throws Exception {
        var groups = new GroupLeftovers(dir);
        groups.remember(project.key(), "api", 0, "m3", "x");
        Process p = spawn("m3", "sleep 120");
        waitFor(() -> GroupLeftovers.carriers("m3", 0).contains(p.pid()));
        Path record = dir.resolve(project.key()).resolve("m3.group");
        Files.writeString(record, Files.readString(record).replaceAll("boot=.*", "boot=another-boot"));

        assertThat(groups.find(project)).isEmpty();
        assertThat(p.isAlive()).isTrue();
    }

    @Test
    void unreadableOrMarkerlessRecordsTouchNothing() throws Exception {
        var groups = new GroupLeftovers(dir);
        Process p = spawn("m4", "sleep 120");
        Path folder = Files.createDirectories(dir.resolve(project.key()));
        Files.writeString(folder.resolve("broken.group"), "notBefore=not-a-number\nmarker=m4\n");
        Files.writeString(folder.resolve("nomarker.group"), "notBefore=0\nboot=" + GroupLeftovers.bootId() + "\n");
        Files.writeString(folder.resolve("m4.tmp"), "half a record from a kill mid-write\n");

        assertThat(groups.find(project)).isEmpty();
        assertThat(p.isAlive()).isTrue();
        assertThat(GroupLeftovers.carriers("", 0)).isEmpty();
    }

    @Test
    void removingSomethingWhoseRecordIsGoneSignalsNobody() throws Exception {
        var groups = new GroupLeftovers(dir);
        Process p = spawn("m5", "sleep 120");
        var leftover = new Leftover(
                "api",
                Leftover.Kind.PROCESS,
                project.key() + "/m5",
                java.util.Set.of(p.pid()),
                "x",
                java.util.Optional.empty());

        assertThat(groups.remove(leftover)).isEqualTo("stopped");
        assertThat(p.isAlive()).isTrue(); // the pid in the leftover is never trusted on its own
    }

    @Test
    void blankOwnerOrMarkerIsNeverRecorded() throws Exception {
        var groups = new GroupLeftovers(dir);
        groups.remember("", "run", 0, "m6", "devdesk run");
        groups.remember(project.key(), "probe", 0, "", "curl");
        groups.forget("", "m6");

        assertThat(dir).isEmptyDirectory();
    }

    @Test
    void theFolderFollowsXdgStateHome() {
        assertThat(GroupLeftovers.defaultFolder(Map.of("XDG_STATE_HOME", "/s", "HOME", "/h")))
                .isEqualTo(Path.of("/s/devdesk/groups"));
        assertThat(GroupLeftovers.defaultFolder(Map.of("XDG_STATE_HOME", " ", "HOME", "/h")))
                .isEqualTo(Path.of("/h/.local/state/devdesk/groups"));
        assertThat(GroupLeftovers.defaultFolder(Map.of())).isEqualTo(Path.of("/tmp/.local/state/devdesk/groups"));
        assertThat(GroupLeftovers.bootId()).matches("[0-9a-f-]{36}");
    }
}
