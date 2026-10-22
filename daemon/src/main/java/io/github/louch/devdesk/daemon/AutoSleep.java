package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.resources.ResourceSample;
import io.github.louch.devdesk.core.sleep.IdleTracker;
import io.github.louch.devdesk.core.sleep.WakeOnConnect;
import io.github.louch.devdesk.core.state.ServiceState;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-sleep ({@code sleep_after: 15m} in devdesk.yaml): every 10 s, a running service that nobody used for that long
 * (no connection to its ports, almost no CPU) is stopped to free its memory, and DevDesk listens on its ports in its
 * place; the first connection starts it again and is passed through once it listens.
 */
final class AutoSleep {

    /** How long a held first connection waits for the woken service (a cold JVM or a big container can be slow). */
    static final Duration WAKE_WAIT = Duration.ofSeconds(90);

    private final EngineSession session;
    private final WakeOnConnect wake;
    private final Clock clock;
    private final IdleTracker idle = new IdleTracker();
    /** Per sleeping service: the stand-ins on its ports. */
    private final Map<String, List<WakeOnConnect.Waiting>> standIns = new ConcurrentHashMap<>();

    AutoSleep(EngineSession session, WakeOnConnect wake, Clock clock) {
        this.session = session;
        this.wake = wake;
        this.clock = clock;
        // Anything the user starts or stops by hand first takes back the ports DevDesk holds for sleepers.
        session.onBeforeStart(this::releaseAll);
    }

    void start() {
        Thread.ofVirtual().name("auto-sleep").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10_000);
                    look();
                } catch (InterruptedException e) {
                    return;
                } catch (RuntimeException e) {
                    // one failed look doesn't end auto-sleep
                }
            }
        });
    }

    private void look() {
        var current = session.current();
        if (current.isEmpty()) {
            return;
        }
        Map<String, ResourceSample> cpu = session.latestResources();
        for (ServiceSpec s : current.get().project().services().values()) {
            if (s.sleepAfter().isEmpty() || standIns.containsKey(s.name())) {
                continue;
            }
            ServiceState state = current.get().states().get(s.name());
            if (!(state instanceof ServiceState.Running || state instanceof ServiceState.Healthy)) {
                idle.forget(s.name());
                continue;
            }
            ResourceSample r = cpu.get(s.name());
            boolean busy = session.connectionsInto(s.name(), ports(s)) > 0
                    || (r != null && r.cpuPercent() > IdleTracker.BUSY_CPU_PERCENT);
            if (idle.idleFor(s.name(), busy, clock.instant(), s.sleepAfter().get())) {
                fallAsleep(s);
            }
        }
    }

    private void fallAsleep(ServiceSpec s) {
        session.markSleeping(s.name(), true);
        standIns.put(s.name(), new ArrayList<>());
        session.note(
                s.name(),
                "asleep: nobody used it for " + minutes(s.sleepAfter().get()) + ". The next connection to port "
                        + s.ports().getFirst() + " wakes it.");
        session.sleep(s.name()).thenRun(() -> {
            List<WakeOnConnect.Waiting> list = standIns.get(s.name());
            if (list == null) {
                return; // released meanwhile (the user started something by hand)
            }
            for (int port : s.ports()) {
                try {
                    list.add(wake.listen(port, () -> wakeUp(s.name()), WAKE_WAIT));
                } catch (IllegalStateException e) {
                    session.note(
                            s.name(),
                            "can't hold port " + port + " while asleep (" + e.getMessage()
                                    + "): start it by hand when needed");
                }
            }
        });
    }

    private void wakeUp(String service) {
        List<WakeOnConnect.Waiting> list = standIns.remove(service);
        if (list != null) {
            list.forEach(WakeOnConnect.Waiting::close); // its other ports too: the service needs all of them
        }
        idle.forget(service);
        session.markSleeping(service, false);
        session.note(service, "waking up: a connection came in");
        session.wake(service);
    }

    private void releaseAll() {
        for (Map.Entry<String, List<WakeOnConnect.Waiting>> e : standIns.entrySet()) {
            e.getValue().forEach(WakeOnConnect.Waiting::close);
            session.markSleeping(e.getKey(), false);
            idle.forget(e.getKey());
        }
        standIns.clear();
    }

    /** The ports a connection to it arrives on: the declared ones, and inside a container its own ports. */
    private static Set<Integer> ports(ServiceSpec s) {
        Set<Integer> out = new LinkedHashSet<>(s.ports());
        if (s.workload() instanceof Workload.Container c) {
            c.ports().forEach(p -> out.add(p.containerPort()));
        }
        return out;
    }

    private static String minutes(Duration d) {
        return d.toMinutes() >= 1 ? d.toMinutes() + " min" : d.toSeconds() + " s";
    }
}
