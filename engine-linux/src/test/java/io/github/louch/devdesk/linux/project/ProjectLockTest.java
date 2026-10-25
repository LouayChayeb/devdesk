package io.github.louch.devdesk.linux.project;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectLockTest {

    @TempDir
    Path runtimeDir;

    private final ProjectSpec project = new ProjectSpec("shop", Path.of("/work/shop"), Map.of(), Map.of());

    @Test
    void secondOwnerIsToldWhoHasItAndMayTakeOverAfterRelease() {
        var env = Map.of("XDG_RUNTIME_DIR", runtimeDir.toString());
        var first = ProjectLock.acquire(env, project, "devdesk up");
        assertThat(first).isInstanceOf(ProjectLock.Result.Acquired.class);

        var second = ProjectLock.acquire(env, project, "the desktop app");
        assertThat(second)
                .isInstanceOfSatisfying(
                        ProjectLock.Result.Busy.class,
                        b -> assertThat(b.owner())
                                .startsWith("PID " + ProcessHandle.current().pid() + ", devdesk up, since "));

        ((ProjectLock.Result.Acquired) first).lock().close();
        var third = ProjectLock.acquire(env, project, "the desktop app");
        assertThat(third).isInstanceOf(ProjectLock.Result.Acquired.class);
        ((ProjectLock.Result.Acquired) third).lock().close();
    }

    @Test
    void differentProjectsDontBlockEachOther() {
        var env = Map.of("XDG_RUNTIME_DIR", runtimeDir.toString());
        var other = new ProjectSpec("shop", Path.of("/elsewhere/shop"), Map.of(), Map.of()); // same name, other folder
        var a = ProjectLock.acquire(env, project, "a");
        var b = ProjectLock.acquire(env, other, "b");

        assertThat(a).isInstanceOf(ProjectLock.Result.Acquired.class);
        assertThat(b).isInstanceOf(ProjectLock.Result.Acquired.class);
        ((ProjectLock.Result.Acquired) a).lock().close();
        ((ProjectLock.Result.Acquired) b).lock().close();
    }

    @Test
    void busyOwnerNamesItsPid() {
        assertThat(new ProjectLock.Result.Busy("PID 4121, devdesk up in /work, since 09:00").pid())
                .contains(4121L);
        assertThat(new ProjectLock.Result.Busy("another DevDesk").pid()).isEmpty();
    }

    @Test
    void anotherProcessHoldingTheLockMakesItBusyUntilThatProcessDies() throws Exception {
        java.nio.file.Files.createDirectories(runtimeDir.resolve("devdesk"));
        Path file = runtimeDir.resolve("devdesk").resolve(project.key() + ".lock");
        // A different process takes the same kernel record lock (fcntl, like Java's FileChannel.tryLock) and writes
        // nothing: an owner that didn't say who it is.
        Process holder = new ProcessBuilder(
                        "python3",
                        "-c",
                        "import fcntl,sys,time; f=open(sys.argv[1],'a+'); fcntl.lockf(f, fcntl.LOCK_EX);"
                                + " print('locked', flush=True); time.sleep(120)",
                        file.toString())
                .start();
        try {
            assertThat(new String(holder.getInputStream().readNBytes(7))).isEqualTo("locked\n");
            var env = Map.of("XDG_RUNTIME_DIR", runtimeDir.toString());

            assertThat(ProjectLock.acquire(env, project, "devdesk up"))
                    .isEqualTo(new ProjectLock.Result.Busy("another DevDesk"));

            holder.destroyForcibly().waitFor(); // SIGKILL: no chance to unlock, the kernel drops the lock itself
            var after = ProjectLock.acquire(env, project, "devdesk up");
            assertThat(after).isInstanceOf(ProjectLock.Result.Acquired.class);
            assertThat(java.nio.file.Files.readString(file))
                    .startsWith("PID " + ProcessHandle.current().pid() + ", devdesk up, since ");
            ((ProjectLock.Result.Acquired) after).lock().close();
        } finally {
            holder.destroyForcibly();
        }
    }

    @Test
    void withoutXdgRuntimeDirTheLockLivesInHome() {
        var env = Map.of("XDG_RUNTIME_DIR", "", "HOME", runtimeDir.toString());
        var a = ProjectLock.acquire(env, project, "a");

        assertThat(runtimeDir.resolve(".local/state/devdesk/" + project.key() + ".lock"))
                .exists();
        assertThat(ProjectLock.acquire(env, project, "b")).isInstanceOf(ProjectLock.Result.Busy.class);
        ((ProjectLock.Result.Acquired) a).lock().close();
    }

    @Test
    void aFolderThatCantBeCreatedDoesntBlockTheUser() throws Exception {
        Path notAFolder = java.nio.file.Files.writeString(runtimeDir.resolve("file"), "x");
        var env = Map.of("XDG_RUNTIME_DIR", notAFolder.toString());

        var a = ProjectLock.acquire(env, project, "a");
        var b = ProjectLock.acquire(env, project, "b");

        // Both get a lock that guards nothing: better than refusing to start (a warning is printed instead).
        assertThat(a).isInstanceOf(ProjectLock.Result.Acquired.class);
        assertThat(b).isInstanceOf(ProjectLock.Result.Acquired.class);
        ((ProjectLock.Result.Acquired) a).lock().close();
    }
}
