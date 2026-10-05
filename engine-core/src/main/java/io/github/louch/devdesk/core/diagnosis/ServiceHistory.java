package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.resources.ResourceSample;
import java.util.List;
import java.util.Set;

/**
 * What happened to one service recently: the facts rules reason about.
 *
 * @param recentOutput the last lines it printed (crash reasons are usually there)
 * @param exits its last exits, oldest first
 * @param probes its last health check results, oldest first
 * @param resources CPU and memory samples over the last half hour, oldest first
 * @param everHealthy whether a health check ever passed in this run
 * @param pids processes it owns right now (empty if it's not running)
 * @param stoppedByDevDesk true if the last exit came from DevDesk's own stop, not from the outside
 */
public record ServiceHistory(
        List<OutputLine> recentOutput,
        List<ExitStatus> exits,
        List<ProbeResult> probes,
        List<ResourceSample> resources,
        boolean everHealthy,
        Set<Long> pids,
        boolean stoppedByDevDesk) {

    public static final ServiceHistory EMPTY =
            new ServiceHistory(List.of(), List.of(), List.of(), List.of(), false, Set.of(), false);

    public ServiceHistory {
        recentOutput = List.copyOf(recentOutput);
        exits = List.copyOf(exits);
        probes = List.copyOf(probes);
        resources = List.copyOf(resources);
        pids = Set.copyOf(pids);
    }
}
