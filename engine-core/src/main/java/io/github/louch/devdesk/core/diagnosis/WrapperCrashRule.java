package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StreamKind;
import io.github.louch.devdesk.core.project.Workload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app crashed inside a watcher that stays alive (nodemon), so the process DevDesk started still runs and looks
 * fine. Its output says so: "[nodemon] app crashed". A taken port (EADDRINUSE) gets the port-conflict explanation.
 */
public final class WrapperCrashRule implements DiagnosisRule {

    private static final int LAST_LINES = 10;
    private static final Pattern CRASHED = Pattern.compile("\\[nodemon\\] app crashed");
    private static final Pattern RESTARTED = Pattern.compile("\\[nodemon\\] (starting|restarting)");
    /** Node's "listen EADDRINUSE: address already in use :::3000" and the same from most other runtimes. */
    private static final Pattern IN_USE =
            Pattern.compile("(?:EADDRINUSE|[Aa]ddress already in use)\\D*?(?:[:\\]](\\d{2,5}))\\b");

    @Override
    public String id() {
        return "wrapper-crash";
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        if (!ctx.stateOf(service).isActive()
                || !(ctx.project().services().get(service).workload() instanceof Workload.Command)) {
            return List.of(); // stopped or crashed services are CrashRule's
        }
        List<OutputLine> lines = ctx.historyOf(service).recentOutput();
        int crashed = lastIndex(lines, CRASHED);
        if (crashed < 0 || lastIndex(lines, RESTARTED) > crashed) {
            return List.of(); // never crashed, or nodemon started the app again after it
        }
        List<OutputLine> before = lines.subList(0, crashed);
        Optional<Integer> port = before.stream()
                .map(l -> IN_USE.matcher(l.text()))
                .filter(Matcher::find)
                .map(m -> Integer.parseInt(m.group(1)))
                .reduce((a, b) -> b);
        if (port.isPresent()) {
            return List.of(portTaken(ctx, service, port.get()));
        }
        List<OutputLine> errors =
                before.stream().filter(l -> l.stream() == StreamKind.STDERR).toList();
        List<OutputLine> shown = errors.isEmpty() ? before : errors;
        List<String> evidence = new ArrayList<>();
        shown.subList(Math.max(0, shown.size() - LAST_LINES), shown.size()).stream()
                .map(l -> (l.stream() == StreamKind.STDERR ? "stderr | " : "stdout | ") + l.text())
                .distinct()
                .forEach(evidence::add);
        evidence.add("then: " + lines.get(crashed).text());
        return List.of(new Finding(
                id(),
                Finding.Severity.ERROR,
                service,
                service + "'s app crashed (nodemon keeps running)",
                service + " runs under nodemon, which stays alive after the app crashes and waits for a file change,"
                        + " so the process looks fine but nothing serves. The lines before the crash say why.",
                evidence,
                List.of(
                        "fix the error shown, then save a file (nodemon restarts it) or restart " + service,
                        "add a health check to " + service + " in devdesk.yaml so DevDesk notices this by itself")));
    }

    private Finding portTaken(DiagnosisContext ctx, String service, int port) {
        Optional<Listener> taken = ctx.system().listenerOn(port);
        String who = taken.map(l -> PortOwners.explain(l, ctx.system(), ctx.project(), ctx.now())
                        .who())
                .orElse("another program (it's gone now)");
        List<String> evidence = new ArrayList<>(List.of("its output: address already in use, port " + port));
        taken.ifPresent(l -> evidence.add("/proc/net/tcp: " + l.address() + ":" + port + " LISTEN, uid " + l.uid()
                + l.pid().map(p -> " → PID " + p).orElse(" (owner not visible without root)")));
        List<String> actions = new ArrayList<>();
        taken.ifPresent(l -> actions.add(
                PortOwners.explain(l, ctx.system(), ctx.project(), ctx.now()).fix()));
        actions.add("or change the port " + service + " uses");
        return new Finding(
                id(),
                Finding.Severity.ERROR,
                service,
                "Port " + port + " is already in use",
                service + "'s app couldn't listen on port " + port + " because it's taken by " + who
                        + ". nodemon keeps running, so the service looks up but nothing serves.",
                evidence,
                actions);
    }

    private static int lastIndex(List<OutputLine> lines, Pattern p) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (p.matcher(lines.get(i).text()).find()) {
                return i;
            }
        }
        return -1;
    }
}
