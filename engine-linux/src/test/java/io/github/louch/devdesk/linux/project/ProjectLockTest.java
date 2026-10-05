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
}
