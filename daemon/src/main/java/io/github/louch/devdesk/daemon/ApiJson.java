package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.changes.Bisect;
import io.github.louch.devdesk.core.changes.ImageProof;
import io.github.louch.devdesk.core.changes.Suspect;
import io.github.louch.devdesk.core.config.ConfigProblem;
import io.github.louch.devdesk.core.data.DatabaseStats;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.DatabaseTargets;
import io.github.louch.devdesk.core.data.KeyValueBrowser;
import io.github.louch.devdesk.core.data.PlanAdvice;
import io.github.louch.devdesk.core.data.QueryPlan;
import io.github.louch.devdesk.core.data.SchemaDiagram;
import io.github.louch.devdesk.core.data.SqlBrowser;
import io.github.louch.devdesk.core.data.SqlEditor;
import io.github.louch.devdesk.core.data.StatsAdvice;
import io.github.louch.devdesk.core.data.StreamAdvice;
import io.github.louch.devdesk.core.diagnosis.Finding;
import io.github.louch.devdesk.core.diagnosis.Ghosts;
import io.github.louch.devdesk.core.graph.DependencyGraph;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.history.HistoryEntry;
import io.github.louch.devdesk.core.history.StartupTimeline;
import io.github.louch.devdesk.core.history.Timeline;
import io.github.louch.devdesk.core.network.Links;
import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.network.PortTable;
import io.github.louch.devdesk.core.project.Dependency;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.resources.ResourceSample;
import io.github.louch.devdesk.core.state.ServiceState;
import io.github.louch.devdesk.core.supervisor.ProjectSupervisor;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.json.JsonMapper;

/**
 * The exact JSON shapes the desktop app receives, built by hand from engine types. Written out explicitly (no
 * annotations, no reflection on domain classes) so the API is visible in one file and can't change by accident.
 */
final class ApiJson {

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ApiJson() {}

    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    static Map<String, Object> project(
            ProjectSpec project,
            String file,
            boolean trusted,
            List<String> commands,
            Map<String, ServiceState> states,
            DependencyGraph graph) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", project.name());
        m.put("root", project.root().toString());
        m.put("file", file);
        m.put("trusted", trusted);
        m.put("warnings", project.warnings()); // allowed but probably not meant, like an unset ${VAR}
        m.put("commands", commands);
        m.put(
                "services",
                project.services().values().stream()
                        .map(s -> service(s, states.getOrDefault(s.name(), new ServiceState.Stopped())))
                        .toList());
        // For the Graph tab: rows from databases (start first) to frontends, and per service what it needs and
        // what breaks if it stops. The app only draws these; the graph logic stays here.
        m.put("levels", graph.startLevels());
        Map<String, Object> reach = new LinkedHashMap<>();
        project.services().keySet().forEach(name -> {
            Set<String> needs = new LinkedHashSet<>(graph.withDependencies(name));
            needs.remove(name);
            reach.put(name, ordered("needs", List.copyOf(needs), "impact", List.copyOf(graph.dependentsOf(name))));
        });
        m.put("reach", reach);
        return m;
    }

    static Map<String, Object> service(ServiceSpec s, ServiceState state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("kind", s.workload() instanceof Workload.Container ? "container" : "process");
        m.put("workload", s.workload().describe());
        m.put("directory", s.directory().toString());
        m.put("ports", s.ports());
        m.put(
                "dependsOn",
                s.dependsOn().stream()
                        .map(d -> Map.of("service", d.service(), "condition", condition(d.condition())))
                        .toList());
        m.put("health", healthType(s.health()));
        m.put("restart", s.restart().name().toLowerCase().replace('_', '-'));
        // Which Workbench views fit this service (the app shows a Data button only where the engine says so).
        m.put("tools", DatabaseTargets.isDatabase(s) ? List.of("data") : List.of());
        m.put("state", state(state));
        return m;
    }

    /** {label, detail, pid, restarts}: label is what the UI colours by, detail is the human reason. */
    static Map<String, Object> state(ServiceState state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", state.label());
        m.put(
                "detail",
                switch (state) {
                    case ServiceState.Blocked b -> b.reason();
                    case ServiceState.Crashed c -> c.exit().explanation();
                    case ServiceState.CrashLoop c ->
                        "keeps crashing, last: " + c.lastExit().explanation();
                    case ServiceState.FailedToStart f -> f.reason();
                    case ServiceState.Unhealthy u -> u.reason();
                    default -> "";
                });
        switch (state) {
            case ServiceState.Running r -> {
                m.put("pid", r.pid());
                m.put("restarts", r.restarts());
            }
            case ServiceState.Healthy h -> {
                m.put("pid", h.pid());
                m.put("restarts", h.restarts());
            }
            case ServiceState.Unhealthy u -> {
                m.put("pid", u.pid());
                m.put("restarts", u.restarts());
            }
            case ServiceState.Blocked b -> m.put("cause", b.cause());
            default -> {}
        }
        return m;
    }

    static Map<String, Object> finding(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", f.ruleId());
        m.put("severity", f.severity().name().toLowerCase());
        m.put("service", f.service());
        m.put("title", f.title());
        m.put("explanation", f.explanation());
        m.put("evidence", f.evidence());
        m.put("actions", f.actions());
        return m;
    }

    /** One ranked change of the "what changed" verdict, with its proof or bisect plan when there is one. */
    static Map<String, Object> verdictItem(Suspect s, Optional<Bisect.Plan> bisect, Optional<ImageProof.Result> proof) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", s.change().kind().name().toLowerCase());
        m.put("subject", s.change().subject());
        m.put("area", s.change().area());
        m.put("what", s.change().what());
        m.put("detail", s.change().detail());
        m.put("confidence", s.confidence().name().toLowerCase());
        m.put("services", s.services());
        m.put("reasons", s.reasons());
        m.put("caveat", s.caveat());
        m.put("provable", s.provable());
        proof.ifPresent(p -> m.put("proof", proofBody(p)));
        bisect.ifPresent(b -> m.put(
                "bisect",
                Map.of(
                        "commands",
                        b.commands(),
                        "commits",
                        b.commits().stream()
                                .limit(20)
                                .map(r -> Map.of(
                                        "id", r.commit().id(),
                                        "subject", r.commit().subject(),
                                        "files", r.commit().files().size(),
                                        "touching", r.touching()))
                                .toList())));
        return m;
    }

    static Map<String, Object> proof(String service, ImageProof.Result r) {
        Map<String, Object> m = proofBody(r);
        m.put("service", service);
        return m;
    }

    private static Map<String, Object> proofBody(ImageProof.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outcome", r.outcome().name().toLowerCase());
        m.put("kind", r.kind() == ImageProof.Kind.CRASH_CHECK ? "crash-check" : "health-check");
        m.put("summary", r.summary());
        m.put("evidence", r.evidence());
        m.put("caveat", r.caveat());
        return m;
    }

    static Map<String, Object> problem(ConfigProblem p) {
        return Map.of(
                "line", p.location().line(), "column", p.location().column(), "path", p.path(), "message", p.message());
    }

    static Map<String, Object> error(String message) {
        return Map.of("error", message);
    }

    private static String condition(Dependency.Condition c) {
        return c.name().toLowerCase();
    }

    private static String healthType(HealthCheckSpec h) {
        return switch (h) {
            case HealthCheckSpec.ProcessAlive p -> "none";
            case HealthCheckSpec.Tcp t -> "tcp " + t.host() + ":" + t.port();
            case HealthCheckSpec.Http x -> "http " + x.url();
            case HealthCheckSpec.Command c -> "command " + c.command();
            case HealthCheckSpec.Docker d -> "docker healthcheck";
        };
    }

    static Map<String, Object> historyItem(Timeline.Item item) {
        HistoryEntry e = item.entry();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", e.at().toString());
        m.put("service", e.service());
        m.put("kind", e.kind().name().toLowerCase());
        m.put("summary", e.summary());
        m.put("data", e.data());
        m.put("routine", item.routine());
        m.put("afterUncleanStop", item.afterUncleanStop());
        return m;
    }

    static Map<String, Object> link(Links.Link l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", l.from());
        m.put("to", l.to().orElse(null)); // null: outside the project
        m.put("target", l.target());
        m.put("open", l.open());
        return m;
    }

    static Map<String, Object> ghost(Ghosts.Ghost g, ProjectSpec project) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", g.process().pid());
        m.put("command", g.process().command());
        m.put(
                "folder",
                g.process()
                        .workingDirectory()
                        .map(d -> project.root().relativize(d).toString())
                        .orElse(null));
        m.put("startedAt", g.process().startedAt().map(Object::toString).orElse(null));
        m.put("ports", List.copyOf(g.ports()));
        m.put("children", g.children());
        m.put("blocks", g.blocks(project));
        return m;
    }

    /** The service drawer (DESIGN.md §4.4). Environment values are never in it, only keys and where they came from. */
    static Map<String, Object> serviceDetails(
            ServiceSpec spec,
            Optional<ProjectSupervisor.ServiceDetails> details,
            List<ServiceEnvironment.Variable> environment,
            List<Integer> listening,
            DependencyGraph graph) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(
                "service",
                service(
                        spec,
                        details.map(ProjectSupervisor.ServiceDetails::state).orElse(new ServiceState.Stopped())));
        m.put("since", details.map(d -> d.since().toString()).orElse(null));
        m.put(
                "pids",
                details.map(d -> List.copyOf(new TreeSet<>(d.history().pids()))).orElse(List.of()));

        List<ProbeResult> probes = details.map(d -> d.history().probes()).orElse(List.of());
        m.put(
                "probes",
                probes.stream()
                        .map(p -> ordered(
                                "ok",
                                p instanceof ProbeResult.Passed,
                                "detail",
                                switch (p) {
                                    case ProbeResult.Passed x -> x.detail();
                                    case ProbeResult.Failed x -> x.detail();
                                }))
                        .toList());
        m.put(
                "lastError",
                probes.reversed().stream()
                        .filter(p -> p instanceof ProbeResult.Failed)
                        .map(p -> ((ProbeResult.Failed) p).detail())
                        .findFirst()
                        .orElse(null));

        List<ResourceSample> samples = details.map(d -> d.history().resources()).orElse(List.of());
        m.put(
                "resources",
                samples.subList(Math.max(0, samples.size() - 150), samples.size()).stream() // 5 min at one per 2 s
                        .map(r -> ordered(
                                "at", r.at().toString(),
                                "cpu", Math.round(r.cpuPercent() * 10) / 10.0,
                                "memory", r.memoryBytes(),
                                "sockets", r.sockets()))
                        .toList());

        // Declared vs really listening. Containers listen inside their own network namespace, so we can't compare.
        boolean comparable = !(spec.workload() instanceof Workload.Container)
                && details.map(d -> d.state().isActive()).orElse(false);
        Map<String, Object> ports = new LinkedHashMap<>();
        ports.put("declared", spec.ports());
        ports.put("listening", comparable ? listening : null);
        ports.put(
                "notListening",
                comparable
                        ? spec.ports().stream()
                                .filter(p -> !listening.contains(p))
                                .toList()
                        : List.of());
        ports.put(
                "undeclared",
                comparable
                        ? listening.stream()
                                .filter(p -> !spec.ports().contains(p))
                                .toList()
                        : List.of());
        m.put("ports", ports);

        m.put(
                "environment",
                environment.stream()
                        .map(v -> ordered(
                                "key", v.key(),
                                "source", v.source().name().toLowerCase().replace('_', '-'),
                                "secret", v.looksSecret()))
                        .toList());
        m.put("dependsOn", List.copyOf(new TreeSet<>(graph.directDependencies(spec.name()))));
        m.put("neededBy", List.copyOf(new TreeSet<>(graph.directDependents(spec.name()))));
        return m;
    }

    static Map<String, Object> port(PortTable.Row r) {
        Listener l = r.listener();
        return ordered(
                "port", l.port(),
                "address", l.address(),
                "pid", l.pid().orElse(null),
                "command", l.command().orElse(null),
                "uid", l.uid(),
                "owner", r.owner().name().toLowerCase().replace('_', '-'),
                "who", r.who().orElse(null),
                "conflict", r.conflict(),
                "fix", r.conflict() ? l.suggestion() : null);
    }

    /**
     * A database the Workbench found, and what DevDesk can do with it ("features"), so the app only offers what
     * works. The password is never sent. A SQLite file has no service: its state is always "file".
     */
    static Map<String, Object> database(DatabaseTarget t, ServiceState state) {
        List<String> features = switch (t.kind()) {
            case POSTGRES -> List.of("plan", "analyze", "activity", "backup", "export", "import", "stats", "slow");
            case MYSQL -> List.of("plan", "analyze", "activity", "backup", "export", "import", "stats");
            case SQLITE -> List.of("plan", "backup", "export", "import", "stats");
            case MONGO -> List.of("backup");
            case REDIS, OBJECTS, SEARCH -> List.of();
        };
        return ordered(
                "service", t.service(),
                "kind", t.kind().name().toLowerCase(),
                "host", t.host(),
                "port", t.port(),
                "user", t.user(),
                "database", t.database(),
                "state",
                        t.kind() == DatabaseTarget.Kind.SQLITE
                                ? ordered("label", "file", "detail", t.database())
                                : state(state),
                "features", features);
    }

    /** A query plan as a tree, with DevDesk's hints in plain words. */
    static Map<String, Object> plan(QueryPlan p) {
        return ordered(
                "root", planNode(p.root()),
                "planningMs", p.planningMs().orElse(null),
                "executionMs", p.executionMs().orElse(null),
                "hints", PlanAdvice.hints(p),
                "raw", p.raw());
    }

    private static Map<String, Object> planNode(QueryPlan.Node n) {
        return ordered(
                "type", n.type(),
                "relation", n.relation().orElse(null),
                "detail", n.detail(),
                "cost", n.totalCost(),
                "planRows", n.planRows(),
                "actualRows", n.actualRows().orElse(null),
                "removed", n.removedByFilter().orElse(null),
                "ms", n.totalMs().orElse(null),
                "spill", n.sortSpill(),
                "children", n.children().stream().map(ApiJson::planNode).toList());
    }

    /** Other connections to the database ("running queries"). */
    static Map<String, Object> sessions(List<SqlBrowser.Session> sessions) {
        return Map.of(
                "sessions",
                sessions.stream()
                        .map(x -> ordered(
                                "pid", x.pid(),
                                "user", x.user(),
                                "application", x.application(),
                                "client", x.client(),
                                "state", x.state(),
                                "runningMs", x.running().map(Duration::toMillis).orElse(null),
                                "waitingFor", x.waitingFor().orElse(null),
                                "query", x.query()))
                        .toList());
    }

    static Map<String, Object> tables(List<SqlBrowser.Table> tables) {
        return Map.of(
                "tables",
                tables.stream()
                        .map(t -> ordered(
                                "schema", t.schema(), "name", t.name(), "view", t.view(), "rows", t.estimatedRows()))
                        .toList());
    }

    static Map<String, Object> structure(SqlBrowser.Structure s) {
        return ordered(
                "columns",
                s.columns().stream()
                        .map(c -> ordered(
                                "name", c.name(),
                                "type", c.type(),
                                "nullable", c.nullable(),
                                "default", c.defaultValue().orElse(null),
                                "primaryKey", c.primaryKey()))
                        .toList(),
                "indexes",
                s.indexes().stream()
                        .map(x -> ordered("name", x.name(), "columns", x.columns(), "unique", x.unique()))
                        .toList(),
                "foreignKeys",
                s.foreignKeys().stream()
                        .map(f -> ordered(
                                "name", f.name(),
                                "columns", f.columns(),
                                "toSchema", f.toSchema(),
                                "toTable", f.toTable(),
                                "toColumns", f.toColumns()))
                        .toList());
    }

    /** Sizes, table use and users, with DevDesk's hints first. */
    static Map<String, Object> stats(DatabaseStats s) {
        return ordered(
                "databaseBytes", s.databaseBytes(),
                "hints",
                        StatsAdvice.of(s).stream()
                                .map(h -> ordered("table", h.table(), "text", h.text()))
                                .toList(),
                "tables",
                        s.tables().stream()
                                .map(t -> ordered(
                                        "schema", t.schema(),
                                        "name", t.name(),
                                        "rows", t.rows(),
                                        "totalBytes", t.totalBytes(),
                                        "indexBytes", t.indexBytes(),
                                        "seqScans", t.seqScans(),
                                        "indexScans", t.indexScans(),
                                        "deadRows", t.deadRows(),
                                        "lastVacuum",
                                                t.lastVacuum()
                                                        .map(Object::toString)
                                                        .orElse(null),
                                        "lastAnalyze",
                                                t.lastAnalyze()
                                                        .map(Object::toString)
                                                        .orElse(null)))
                                .toList(),
                "roles",
                        s.roles().stream()
                                .map(r -> ordered(
                                        "name", r.name(),
                                        "attributes", r.attributes(),
                                        "memberOf", r.memberOf(),
                                        "privileges", r.privileges()))
                                .toList());
    }

    /** The table diagram: boxes with their columns (key and link columns marked), levels to draw them in, arrows. */
    static Map<String, Object> diagram(
            SchemaDiagram.Diagram d,
            Map<SchemaDiagram.TableName, SqlBrowser.Structure> structures,
            Map<SchemaDiagram.TableName, Long> rows) {
        List<Map<String, Object>> tables = new ArrayList<>();
        structures.forEach((name, s) -> {
            Set<String> linking = new HashSet<>();
            s.foreignKeys().forEach(f -> linking.addAll(f.columns()));
            tables.add(ordered(
                    "id", name.toString(),
                    "schema", name.schema(),
                    "name", name.name(),
                    "rows", rows.getOrDefault(name, -1L),
                    "columns",
                            s.columns().stream()
                                    .map(c -> ordered(
                                            "name", c.name(),
                                            "type", c.type(),
                                            "nullable", c.nullable(),
                                            "primaryKey", c.primaryKey(),
                                            "link", linking.contains(c.name())))
                                    .toList()));
        });
        return ordered(
                "tables", tables,
                "levels",
                        d.levels().stream()
                                .map(l -> l.stream().map(Object::toString).toList())
                                .toList(),
                "links",
                        d.links().stream()
                                .map(l -> ordered(
                                        "name", l.name(),
                                        "from", l.from().toString(),
                                        "columns", l.columns(),
                                        "to", l.to().toString(),
                                        "toColumns", l.toColumns()))
                                .toList(),
                "outside", d.outside().stream().map(Object::toString).sorted().toList());
    }

    static Map<String, Object> result(SqlBrowser.ResultTable r) {
        return ordered(
                "columns", r.columns(),
                "types", r.types(),
                "rows", r.rows(),
                "more", r.more(),
                "affected", r.affected(),
                "ms", r.took().toMillis());
    }

    static Map<String, Object> keyPage(KeyValueBrowser.KeyPage page) {
        return ordered(
                "keys",
                page.keys().stream()
                        .map(k -> ordered("key", k.key(), "type", k.type(), "ttl", seconds(k.ttl())))
                        .toList(),
                "next",
                page.next());
    }

    /** A Redis value, with its shape per type ("text", "items", "fields", "scored", "entries"). */
    static Map<String, Object> keyValue(KeyValueBrowser.KeyValue v) {
        Map<String, Object> m =
                ordered("key", v.key(), "type", v.type(), "ttl", seconds(v.ttl()), "length", v.length());
        switch (v.value()) {
            case KeyValueBrowser.Value.Text t -> m.put("text", t.text());
            case KeyValueBrowser.Value.Items i -> m.put("items", i.items());
            case KeyValueBrowser.Value.Fields f -> m.put("fields", fields(f.fields()));
            case KeyValueBrowser.Value.Scored s ->
                m.put(
                        "scored",
                        s.members().stream()
                                .map(x -> ordered("member", x.member(), "score", x.score()))
                                .toList());
            case KeyValueBrowser.Value.Entries e ->
                m.put(
                        "entries",
                        e.entries().stream()
                                .map(x -> ordered("id", x.id(), "fields", fields(x.fields())))
                                .toList());
            case KeyValueBrowser.Value.Unsupported u -> m.put("unsupported", u.type());
        }
        return m;
    }

    private static List<Map<String, Object>> fields(List<KeyValueBrowser.Field> fields) {
        return fields.stream()
                .map(f -> ordered("name", f.name(), "value", f.value()))
                .toList();
    }

    /** Seconds with milliseconds, or null for "never expires". */
    private static Double seconds(Optional<Duration> ttl) {
        return ttl.map(d -> d.toMillis() / 1000.0).orElse(null);
    }

    /** Where Redis's memory goes. */
    static Map<String, Object> stream(String key, KeyValueBrowser.StreamInfo s) {
        return ordered(
                "length", s.length(),
                "firstId", s.firstId(),
                "lastId", s.lastId(),
                "hints", StreamAdvice.of(key, s),
                "groups",
                        s.groups().stream()
                                .map(g -> ordered(
                                        "name", g.name(),
                                        "pending", g.pending(),
                                        "lastDelivered", g.lastDelivered(),
                                        "lag", g.lag(),
                                        "consumers",
                                                g.consumers().stream()
                                                        .map(c -> ordered(
                                                                "name", c.name(),
                                                                "pending", c.pending(),
                                                                "idleMs",
                                                                        c.idle().toMillis()))
                                                        .toList()))
                                .toList());
    }

    static Map<String, Object> server(KeyValueBrowser.ServerStats s) {
        return ordered(
                "at", s.at().toString(),
                "version", s.version(),
                "uptimeSeconds", s.uptimeSeconds(),
                "opsPerSecond", s.opsPerSecond(),
                "clients", s.clients(),
                "blockedClients", s.blockedClients(),
                "usedBytes", s.usedBytes(),
                "keys", s.keys(),
                "hits", s.hits(),
                "misses", s.misses(),
                "expiredKeys", s.expiredKeys(),
                "evictedKeys", s.evictedKeys(),
                "netInBytes", s.netInBytes(),
                "netOutBytes", s.netOutBytes());
    }

    static Map<String, Object> memory(KeyValueBrowser.MemoryReport m) {
        return ordered(
                "usedBytes", m.usedBytes(),
                "maxBytes", m.maxBytes(),
                "policy", m.policy(),
                "evicted", m.evicted(),
                "totalKeys", m.totalKeys(),
                "scanned", m.scanned(),
                "biggest",
                        m.biggest().stream()
                                .map(k -> ordered(
                                        "key", k.key(), "type", k.type(), "bytes", k.bytes(), "length", k.length()))
                                .toList(),
                "prefixes",
                        m.prefixes().stream()
                                .map(p -> ordered("prefix", p.prefix(), "keys", p.keys(), "bytes", p.bytes()))
                                .toList());
    }

    static Map<String, Object> slowLog(KeyValueBrowser.SlowLog log) {
        return ordered(
                "thresholdMicros", log.thresholdMicros(),
                "entries",
                        log.entries().stream()
                                .map(e -> ordered(
                                        "id", e.id(),
                                        "at", e.at().toString(),
                                        "micros", e.micros(),
                                        "words", e.words(),
                                        "client", e.client()))
                                .toList());
    }

    static Map<String, Object> applied(SqlEditor.Applied a) {
        return ordered("rows", a.rows(), "ms", a.took().toMillis());
    }

    /** A small JSON object whose keys keep this order (Map.of has none), so the API reads the same every time. */
    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    /** A startup for the chart: times in milliseconds from its beginning; null = never got there. */
    static Map<String, Object> startup(StartupTimeline.Startup s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("began", s.began().toString());
        m.put("tookMs", s.took().map(Duration::toMillis).orElse(null));
        m.put(
                "services",
                s.services().stream()
                        .map(r -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("service", r.service());
                            row.put("requestedMs", r.requested().toMillis());
                            row.put(
                                    "startingMs",
                                    r.starting().map(Duration::toMillis).orElse(null));
                            row.put(
                                    "runningMs",
                                    r.running().map(Duration::toMillis).orElse(null));
                            row.put("readyMs", r.ready().map(Duration::toMillis).orElse(null));
                            row.put("endedMs", r.ended().map(Duration::toMillis).orElse(null));
                            row.put("waitedFor", r.waitedFor().orElse(null));
                            row.put("dependsOn", r.dependsOn());
                            row.put("outcome", r.outcome());
                            row.put("slowestChain", r.slowestChain());
                            row.put("detail", r.detail());
                            return row;
                        })
                        .toList());
        return m;
    }
}
