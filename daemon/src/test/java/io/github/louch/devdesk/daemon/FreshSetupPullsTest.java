package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.setup.FreshSetup;
import io.github.louch.devdesk.docker.PullLog;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Set up's downloads are DevDesk's only when they weren't on the machine before: Remove never takes yours. */
class FreshSetupPullsTest {

    @TempDir
    Path tmp;

    @Test
    void onlyAnImageThatWasntHereBeforeIsRecordedForThisProject() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("XDG_STATE_HOME", tmp.resolve("state").toString());
        var steps = List.<FreshSetup.Step>of(
                new FreshSetup.Step.Run("pull:mine:1", "Download mine:1", "", "true"),
                new FreshSetup.Step.Run("pull:new:1", "Download new:1", "", "true"),
                new FreshSetup.Step.Run("pull:broken:1", "Download broken:1", "", "false"));
        // A fake Docker: mine:1 is the user's, there from the start; new:1 is there only after its step ran.
        Set<String> asked = new HashSet<>();
        var honest = new FreshSetupRunner(null, env, image -> {
            boolean secondTime = !asked.add(image);
            return image.equals("mine:1") || (image.equals("new:1") && secondTime);
        });
        assertThat(honest.start(tmp, "shop", steps, Map.of())).isEmpty();
        while (Boolean.TRUE.equals(honest.status().get("running"))) {
            Thread.sleep(50);
        }

        var log = new PullLog(Optional.of(PullLog.defaultFile(env)));
        String key = ProjectSpec.keyOf("shop", tmp);
        assertThat(log.pulledFor(key)).containsExactly("new:1"); // not mine:1 (yours), not broken:1 (failed)
    }

    @Test
    void aFailedStepDoesntSkipTheIndependentOnesOnlyTheJobsAfterIt() throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("XDG_STATE_HOME", tmp.resolve("state").toString());
        var runner = new FreshSetupRunner(null, env, image -> false);
        var steps = List.<FreshSetup.Step>of(
                new FreshSetup.Step.Run("install:streaming", "npm install in ./streaming", "", "false"),
                new FreshSetup.Step.Run("install:", "Download Go modules", "", "true"),
                new FreshSetup.Step.Job("job:migrate", "Run migrate once", "migrate"));

        runner.start(tmp, "shop", steps, Map.of());
        while (Boolean.TRUE.equals(runner.status().get("running"))) {
            Thread.sleep(50);
        }

        @SuppressWarnings("unchecked")
        var done = (List<Map<String, Object>>) runner.status().get("steps");
        assertThat(done).extracting(m -> m.get("status")).containsExactly("failed", "ok", "skipped");
        assertThat(done.get(2).get("detail")).asString().contains("an earlier step failed");
    }
}
