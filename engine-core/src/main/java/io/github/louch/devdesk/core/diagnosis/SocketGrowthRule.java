package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.network.Connection;
import io.github.louch.devdesk.core.network.Links;
import io.github.louch.devdesk.core.resources.ResourceSample;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A running service keeps opening sockets and never closes them: a connection leak. It ends in "too many open files"
 * or the database refusing new clients, often hours later. Checked continuously, like {@link MemoryGrowthRule}.
 */
public final class SocketGrowthRule implements DiagnosisRule {

    static final Duration MIN_WINDOW = Duration.ofMinutes(10);
    static final int MIN_GROWTH = 50;
    static final double MIN_GROWTH_RATIO = 2;
    static final double MIN_R_SQUARED = 0.8;

    @Override
    public String id() {
        return "socket-growth";
    }

    @Override
    public boolean continuous() {
        return true;
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        List<ResourceSample> samples = ctx.historyOf(service).resources();
        if (samples.size() < 20
                || Duration.between(samples.getFirst().at(), samples.getLast().at())
                                .compareTo(MIN_WINDOW)
                        < 0) {
            return List.of();
        }
        Trend fit = Trend.of(samples, ResourceSample::sockets);
        double start = Trend.start(samples, ResourceSample::sockets);
        double end = Trend.end(samples, ResourceSample::sockets);
        boolean grew = end - start >= MIN_GROWTH && end >= start * MIN_GROWTH_RATIO;
        if (fit.slopePerMinute() <= 0 || !grew || fit.rSquared() < MIN_R_SQUARED) {
            return List.of();
        }
        if (receivingEnd(ctx, service)) {
            return List.of(); // its sockets grow because a caller leaks connections *to* it; that caller is reported
        }
        long minutes = Math.max(
                1,
                Duration.between(samples.getFirst().at(), samples.getLast().at())
                        .toMinutes());
        List<String> evidence = new ArrayList<>();
        evidence.add(samples.size() + " samples over " + minutes + " min, straight-line fit r² = "
                + String.format("%.2f", fit.rSquared()) + " (1.00 = perfectly steady)");
        String where = whereTheyGo(ctx, service, evidence);
        return List.of(new Finding(
                id(),
                Finding.Severity.WARNING,
                service,
                service + " keeps opening connections and never closes them",
                service + " went from " + Math.round(start) + " to " + Math.round(end) + " open sockets in " + minutes
                        + " minutes (+" + Math.round(fit.slopePerMinute()) + " per minute), steadily" + where
                        + ". Usually a connection that is opened per request and never closed, or a pool without a "
                        + "limit. It ends with \"too many open files\" or the other side refusing new clients.",
                evidence,
                List.of(
                        "look for connections opened per request without close(), or use a pool with a maximum size",
                        "restart " + service + " to free them for now (they will pile up again)")));
    }

    /**
     * True when most of the service's connections were opened by others to its own ports: a database whose client
     * leaks connections looks exactly like a leak, but the fix belongs to the client.
     */
    private static boolean receivingEnd(DiagnosisContext ctx, String service) {
        Set<Long> pids = ctx.historyOf(service).pids();
        if (pids.isEmpty()) {
            return false; // can't tell; better to warn
        }
        Set<Integer> own = new HashSet<>(ctx.project().services().get(service).ports());
        ctx.system().listenersOf(pids).forEach(l -> own.add(l.port()));
        List<Connection> connections = ctx.system().connectionsOf(pids);
        long incoming =
                connections.stream().filter(c -> own.contains(c.localPort())).count();
        return incoming * 2 > connections.size();
    }

    /** ", most of them to db :5433" when the open connections show it. Adds the top destinations to the evidence. */
    private static String whereTheyGo(DiagnosisContext ctx, String service, List<String> evidence) {
        Set<Long> pids = ctx.historyOf(service).pids();
        if (pids.isEmpty()) {
            return "";
        }
        Map<String, Set<Long>> running = new HashMap<>();
        ctx.history().forEach((name, h) -> running.put(name, h.pids()));
        List<Links.Link> links = new ArrayList<>(ProjectLinks.of(ctx.project(), running, ctx.system()));
        links.removeIf(l -> !l.from().equals(service));
        if (links.isEmpty()) {
            return "";
        }
        links.sort(Comparator.comparingInt(Links.Link::open).reversed());
        links.stream().limit(3).forEach(l -> evidence.add("open now: " + describe(l) + "  " + l.open()));
        Links.Link top = links.getFirst();
        int total = links.stream().mapToInt(Links.Link::open).sum();
        return top.open() * 2 > total ? ", most of them to " + describe(top) : "";
    }

    private static String describe(Links.Link l) {
        String port = l.target().substring(l.target().lastIndexOf(':') + 1);
        return l.to().map(to -> to + " :" + port).orElse(l.target());
    }
}
