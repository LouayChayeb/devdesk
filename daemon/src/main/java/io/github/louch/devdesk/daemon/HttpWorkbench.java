package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.DatabaseTargets;
import io.github.louch.devdesk.core.data.QueryLog;
import io.github.louch.devdesk.core.data.RepeatedQueries;
import io.github.louch.devdesk.core.data.SlowQueries;
import io.github.louch.devdesk.core.http.Curl;
import io.github.louch.devdesk.core.http.GraphQl;
import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.HttpCallJson;
import io.github.louch.devdesk.core.http.HttpSender;
import io.github.louch.devdesk.core.http.RecentRequests;
import io.github.louch.devdesk.core.http.RequestBook;
import io.github.louch.devdesk.core.http.RequestBook.Book;
import io.github.louch.devdesk.core.http.RequestBook.SavedRequest;
import io.github.louch.devdesk.core.http.RequestImports;
import io.github.louch.devdesk.core.http.RequestTrouble;
import io.github.louch.devdesk.core.http.RequestVariables;
import io.github.louch.devdesk.core.http.ResponseRules;
import io.github.louch.devdesk.core.http.SecretScan;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * The API client behind the HTTP API: fills in variables, sends requests through the engine, checks answers and keeps
 * values from them, explains failures with what the project knows, and keeps saved requests, variables and history
 * in the project's request file.
 */
final class HttpWorkbench {

    static final String CALL_SHAPE =
            "send {\"method\": \"GET\", \"url\": \"{{api.url}}/health\", \"headers\"?: [{\"name\","
                    + " \"value\"}], \"bodyType\"?: \"text|form|multipart\", \"body\"?, \"form\"?, \"auth\"?, \"checks\"?,"
                    + " \"captures\"?}";
    /** Log lines often arrive a moment after the answer (the service logs after replying): wait this long for them. */
    static final Duration TRACE_GRACE = Duration.ofMillis(300);

    private final EngineSession session;
    private final HttpSender sender;
    private final Function<ProjectSpec, RequestBook> books;
    private final RecentRequests recent;
    private final Clock clock;
    private final RandomGenerator random;
    /** The requests behind {@link RecentRequests} ids, as typed: a crash link in the logs opens the request again. */
    private final Map<Long, HttpCall> sentCalls = new ConcurrentHashMap<>();

    HttpWorkbench(
            EngineSession session,
            HttpSender sender,
            Function<ProjectSpec, RequestBook> books,
            RecentRequests recent,
            Clock clock,
            RandomGenerator random) {
        this.session = session;
        this.sender = sender;
        this.books = books;
        this.recent = recent;
        this.clock = clock;
        this.random = random;
    }

    /** Variables (the services' and the user's), saved requests, history and cookies, for the API page. */
    synchronized EngineSession.Answer overview() {
        return withProject(p -> view(p, books.apply(p).load()));
    }

    /**
     * Sends one request. Variables are filled in first; the history keeps the request as typed (with its
     * {@code {{token}}}), so a secret never lands there in clear text. Checks run on the answer; captures store
     * values from it as variables; the log lines all services printed meanwhile come back as a trace.
     */
    EngineSession.Answer send(Map<?, ?> body) {
        Optional<HttpCall> typed = HttpCallJson.read(body);
        if (typed.isEmpty()) {
            return new EngineSession.Answer(400, ApiJson.error(CALL_SHAPE));
        }
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return noProject();
        }
        Instant started = clock.instant();
        Map<String, Object> answer = exchange(
                current.get().project(),
                typed.get(),
                HttpCallJson.readChecks(body.get("checks")),
                HttpCallJson.readCaptures(body.get("captures")));
        if (answer.containsKey("error")) {
            return new EngineSession.Answer(422, answer);
        }
        // What every service printed while the request ran: "what this request did", across services.
        try {
            Thread.sleep(TRACE_GRACE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<Map<String, Object>> trace = session.linesBetween(started.minusMillis(20), clock.instant());
        answer.put("trace", trace);
        addQueries(current.get().project(), trace, answer);
        return new EngineSession.Answer(200, answer);
    }

    /**
     * The SQL the request made each Postgres of the project run (from its log, when query logging is on), and the
     * rows it created or changed, to open in the Data page.
     */
    private void addQueries(ProjectSpec project, List<Map<String, Object>> trace, Map<String, Object> answer) {
        List<Map<String, Object>> queries = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> repeats = new ArrayList<>();
        String body = answer.get("body") instanceof String b ? b : "";
        for (DatabaseTarget t : DatabaseTargets.find(project)) {
            if (t.kind() != DatabaseTarget.Kind.POSTGRES) {
                continue;
            }
            List<QueryLog.Line> lines = trace.stream()
                    .filter(l -> t.service().equals(l.get("service")))
                    .map(l -> new QueryLog.Line(Instant.parse((String) l.get("at")), (String) l.get("text")))
                    .toList();
            List<QueryLog.Query> found = QueryLog.parse(lines);
            SlowQueries slow = found.isEmpty() ? null : slowQueries(t);
            for (QueryLog.Query q : found) {
                Map<String, Object> m = ordered(
                        "service",
                        t.service(),
                        "at",
                        q.at().toString(),
                        "ms",
                        q.ms(),
                        "sql",
                        q.sql(),
                        "params",
                        q.params());
                // Where this query stands in the database's own slowest-queries list: is it slow every time?
                m.put("shape", SlowQueries.shape(q.sql()));
                Optional<Integer> rank = slow == null ? Optional.empty() : slow.rankOf(q.sql());
                rank.ifPresent(r -> {
                    SlowQueries.Statement st = slow.statements().get(r);
                    m.put(
                            "slow",
                            Map.of("rank", r + 1, "calls", st.calls(), "meanMs", st.meanMs(), "totalMs", st.totalMs()));
                });
                queries.add(m);
            }
            // "N+1": the same query once per item of a list. Each run is fast; all of them make the request slow.
            for (RepeatedQueries.Repeat r : RepeatedQueries.find(found)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("service", t.service());
                m.put("shape", r.shape());
                m.put("example", r.example());
                m.put("times", r.times());
                m.put("distinctValues", r.distinctValues());
                m.put("totalMs", r.totalMs());
                m.put("before", r.before().orElse(null));
                m.put("identical", r.identical());
                m.put("advice", r.advice());
                repeats.add(m);
            }
            for (QueryLog.RowLink r : QueryLog.rows(found, body)) {
                rows.add(ordered(
                        "service", t.service(),
                        "schema", r.schema(),
                        "table", r.table(),
                        "column", r.column(),
                        "value", r.value(),
                        "action", r.action()));
            }
        }
        answer.put("queries", queries);
        answer.put("rows", rows);
        answer.put("repeats", repeats);
    }

    /** The database's slowest-queries list, or null when it isn't there (not set up, or not Postgres). */
    private SlowQueries slowQueries(DatabaseTarget t) {
        try {
            SlowQueries s = session.sql(t).slowQueries(t);
            return s.status() == SlowQueries.Status.READY ? s : null;
        } catch (RuntimeException e) {
            return null; // the request's answer matters more than this extra
        }
    }

    /**
     * Sends every saved request of a collection, in order, like a test run: a log-in request first can capture the
     * token the next ones use. Each answer is checked; the run doesn't stop at a failure.
     */
    EngineSession.Answer run(Map<?, ?> body) {
        if (!(body.get("collection") instanceof String collection)) {
            return new EngineSession.Answer(400, ApiJson.error("send {\"collection\": \"Shop API / orders\"}"));
        }
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return noProject();
        }
        ProjectSpec project = current.get().project();
        List<SavedRequest> requests;
        synchronized (this) {
            requests = books.apply(project).load().saved().stream()
                    .filter(r ->
                            r.collection().equals(collection) || r.collection().startsWith(collection + " / "))
                    .toList();
        }
        if (requests.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("the collection " + collection + " has no requests"));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0;
        for (SavedRequest r : requests) {
            Map<String, Object> a = exchange(project, r.call(), r.checks(), r.captures());
            Map<String, Object> row =
                    ordered("id", r.id(), "name", r.name(), "method", r.call().method());
            row.putAll(a);
            row.remove("body"); // the run view shows outcomes, not bodies
            row.remove("headers");
            boolean ok = !a.containsKey("error")
                    && "answered".equals(a.get("outcome"))
                    && a.get("checks") instanceof List<?> checks
                    && checks.stream().allMatch(c -> c instanceof Map<?, ?> m && Boolean.TRUE.equals(m.get("passed")))
                    && (r.checks().isEmpty() ? (int) a.get("status") < 400 : true);
            row.put("ok", ok);
            passed += ok ? 1 : 0;
            results.add(row);
        }
        return new EngineSession.Answer(
                200, ordered("collection", collection, "passed", passed, "total", results.size(), "results", results));
    }

    /** Fill in, send, check, capture, remember. Not synchronized while sending: a slow server must not block. */
    private Map<String, Object> exchange(
            ProjectSpec project,
            HttpCall typed,
            List<ResponseRules.Check> checks,
            List<ResponseRules.Capture> captures) {
        RequestBook book = books.apply(project);
        Map<String, String> vars;
        synchronized (this) {
            vars = variables(project, book.load());
        }
        RequestVariables.Resolved resolved = RequestVariables.resolve(typed, vars);
        if (!resolved.missing().isEmpty()) {
            return ApiJson.error("unknown variable" + (resolved.missing().size() == 1 ? " " : "s ")
                    + String.join(
                            ", ",
                            resolved.missing().stream()
                                    .map(m -> "{{" + m + "}}")
                                    .toList())
                    + ": add it under Variables, or use one of the services' ({{api.url}}...)");
        }
        Optional<String> service = RequestTrouble.serviceAt(resolved.call().url(), project);
        // Registered before sending: a crash the service logs while still handling it is linked to this request.
        RecentRequests.Sent running =
                recent.start(service.orElse(""), typed.method(), resolved.call().url(), clock.instant());
        sentCalls.put(running.id(), typed);
        sentCalls.keySet().removeIf(id -> id <= running.id() - RecentRequests.KEPT);
        HttpSender.Outcome outcome = sender.send(resolved.call());
        recent.end(running, clock.instant());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("url", resolved.call().url());
        // Which service of the project got it, so the app can show that service's logs right below.
        answer.put("service", service.orElse(null));
        answer.put("sentId", running.id());
        Optional<Integer> status;
        switch (outcome) {
            case HttpSender.Outcome.Answered a -> {
                status = Optional.of(a.status());
                answer.put("outcome", "answered");
                answer.put("status", a.status());
                answer.put("version", a.version());
                answer.put("headers", headers(a.headers()));
                answer.put("body", a.body());
                answer.put("binary", a.binary());
                answer.put("size", a.size());
                answer.put("ms", a.took().toMillis());
                answer.put(
                        "checks",
                        ResponseRules.checkAll(a, checks).stream()
                                .map(r -> ordered(
                                        "label",
                                        ResponseRules.describe(r.check()),
                                        "passed",
                                        r.passed(),
                                        "actual",
                                        r.actual()))
                                .toList());
                answer.put("captured", capture(project, book, a, captures));
            }
            case HttpSender.Outcome.Failed f -> {
                status = Optional.empty();
                // Re-read the states: the explanation must describe the project as it is now.
                EngineSession.Current now = session.current().orElse(new EngineSession.Current(project, Map.of()));
                answer.put("outcome", "failed");
                answer.put("reason", f.reason().name().toLowerCase().replace('_', '-'));
                answer.put(
                        "explanation", RequestTrouble.explain(f, resolved.call().url(), now.project(), now.states()));
                answer.put("detail", f.message());
                answer.put("ms", f.took().toMillis());
                answer.put("checks", List.of());
                answer.put("captured", List.of());
            }
        }
        synchronized (this) {
            book.store(book.load().withSent(new RequestBook.Sent(clock.instant(), typed, status, durationOf(outcome))));
        }
        return answer;
    }

    /** Stores what the captures found as user variables. Secret-looking names are reported without their value. */
    private synchronized List<Map<String, Object>> capture(
            ProjectSpec project,
            RequestBook book,
            HttpSender.Outcome.Answered a,
            List<ResponseRules.Capture> captures) {
        if (captures.isEmpty() || a.status() >= 400) {
            return List.of(); // a failed log-in must not overwrite a good token with an error message
        }
        Book current = book.load();
        Map<String, String> vars = new LinkedHashMap<>(current.variables());
        List<Map<String, Object>> out = new ArrayList<>();
        for (ResponseRules.Capture c : captures) {
            Optional<String> value = ResponseRules.read(a, c.part(), c.path());
            value.ifPresent(v -> vars.put(c.variable(), v));
            boolean secret = ServiceEnvironment.looksSecret(c.variable());
            out.add(ordered(
                    "variable",
                    c.variable(),
                    "found",
                    value.isPresent(),
                    "value",
                    value.map(v -> secret ? "••••" : v.length() > 80 ? v.substring(0, 80) + "…" : v)
                            .orElse(null)));
        }
        book.store(current.withVariables(vars));
        return out;
    }

    /** Saves a request (new when it has no id yet). Answers with the whole overview, so the page just redraws. */
    synchronized EngineSession.Answer save(Map<?, ?> body) {
        Optional<HttpCall> call = HttpCallJson.read(body);
        if (call.isEmpty() || !(body.get("name") instanceof String name) || name.isBlank()) {
            return new EngineSession.Answer(
                    400, ApiJson.error("send {\"name\", \"collection\"?, \"id\"?, ...the request}"));
        }
        String id = body.get("id") instanceof String s && !s.isBlank() ? s : newId();
        String collection = body.get("collection") instanceof String c ? c.strip() : "";
        return withProject(p -> {
            RequestBook book = books.apply(p);
            Book next = book.load()
                    .withSaved(new SavedRequest(
                            id,
                            name.strip(),
                            collection,
                            call.get(),
                            HttpCallJson.readChecks(body.get("checks")),
                            HttpCallJson.readCaptures(body.get("captures"))));
            book.store(next);
            Map<String, Object> view = view(p, next);
            view.put("id", id);
            return view;
        });
    }

    synchronized EngineSession.Answer delete(String id) {
        return withProject(p -> {
            RequestBook book = books.apply(p);
            Book next = book.load().without(id);
            book.store(next);
            return view(p, next);
        });
    }

    /** Replaces the user's variables: {"variables": {"token": "..."}}. */
    synchronized EngineSession.Answer variables(Map<?, ?> body) {
        if (!(body.get("variables") instanceof Map<?, ?> m)) {
            return new EngineSession.Answer(400, ApiJson.error("send {\"variables\": {\"name\": \"value\"}}"));
        }
        Map<String, String> vars = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String k)
                    || !k.matches("[A-Za-z0-9_.-]+")
                    || !(e.getValue() instanceof String v)) {
                return new EngineSession.Answer(
                        400, ApiJson.error("variable names use letters, digits, '.', '-' and '_'; values are text"));
            }
            vars.put(k, v);
        }
        return withProject(p -> {
            RequestBook book = books.apply(p);
            Book next = book.load().withVariables(vars);
            book.store(next);
            return view(p, next);
        });
    }

    /**
     * {collection, shared}: moves a collection into the project's folder (.devdesk/requests/, for git) or back. Sharing
     * is refused while a request in it has a token or password typed in: it must use a {{variable}}, whose value stays
     * on this machine.
     */
    synchronized EngineSession.Answer share(Map<?, ?> body) {
        if (!(body.get("collection") instanceof String collection) || !(body.get("shared") instanceof Boolean shared)) {
            return new EngineSession.Answer(
                    400, ApiJson.error("send {\"collection\": \"Shop API\", \"shared\": true}"));
        }
        return withProject(p -> {
            RequestBook book = books.apply(p);
            if (shared) {
                List<String> secrets = new ArrayList<>();
                book.load().saved().stream()
                        .filter(r -> r.collection().equals(collection))
                        .forEach(r -> secrets.addAll(SecretScan.of(r.name(), r.call())));
                if (!secrets.isEmpty()) {
                    throw new IllegalStateException("These requests have a secret typed in, which would go into git: "
                            + String.join("; ", secrets) + ". Put it in a variable instead (Variables, then {{token}}):"
                            + " variables stay on this machine.");
                }
            }
            book.setShared(collection, shared);
            return view(p, book.load());
        });
    }

    synchronized EngineSession.Answer clearCookies() {
        sender.clearCookies();
        return overview();
    }

    // ---- other tools' formats ----

    /**
     * {service} fetches the service's OpenAPI description from where frameworks publish it (NestJS: /api-json);
     * {content} reads a pasted or picked file. The requests are saved in a collection named after the API.
     */
    EngineSession.Answer importOpenApi(Map<?, ?> body) {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return noProject();
        }
        ProjectSpec project = current.get().project();
        String service = body.get("service") instanceof String s ? s : "";
        String base = service.isEmpty() ? "{{base.url}}" : "{{" + service + ".url}}";
        String text;
        Optional<String> file;
        try {
            file = fileText(body);
        } catch (IllegalArgumentException e) {
            return new EngineSession.Answer(422, ApiJson.error(e.getMessage()));
        }
        if (file.isPresent()) {
            text = file.get();
        } else if (!service.isEmpty()) {
            String url = RequestVariables.ofServices(project).get(service + ".url");
            if (url == null) {
                return new EngineSession.Answer(
                        404, ApiJson.error(service + " has no port in devdesk.yaml, so DevDesk can't reach it"));
            }
            Optional<String> found = fetchDescription(url);
            if (found.isEmpty()) {
                return new EngineSession.Answer(
                        404,
                        ApiJson.error("no OpenAPI description at " + url + " (tried "
                                + String.join(", ", RequestImports.OPENAPI_PATHS) + "). In NestJS, call"
                                + " SwaggerModule.setup('api', app, document): the JSON is then at /api-json. Or"
                                + " import the file instead."));
            }
            text = found.get();
        } else {
            return new EngineSession.Answer(
                    400, ApiJson.error("send {\"service\": \"api\"}, {\"content\": \"...\"} or {\"path\": \"...\"}"));
        }
        try {
            RequestImports.Description d = RequestImports.openApi(text, base);
            return saveImported(project, d.requests(), "OpenAPI: " + d.title());
        } catch (IllegalArgumentException e) {
            return new EngineSession.Answer(422, ApiJson.error(e.getMessage()));
        }
    }

    /** Files bigger than this aren't a collection or an API description. */
    static final long MAX_IMPORT_BYTES = 20L * 1024 * 1024;

    /**
     * The text to import: {content} pasted or read by the page itself, or {path} picked in the app's file dialog (the
     * webview can't read local files, so the engine does).
     */
    static Optional<String> fileText(Map<?, ?> body) {
        if (body.get("content") instanceof String c && !c.isBlank()) {
            return Optional.of(c);
        }
        if (!(body.get("path") instanceof String p) || p.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(p);
        try {
            if (Files.size(path) > MAX_IMPORT_BYTES) {
                throw new IllegalArgumentException(p + " is bigger than 20 MB: not an API description");
            }
            return Optional.of(Files.readString(path));
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalArgumentException("couldn't read " + p + ": " + e.getMessage());
        }
    }

    private Optional<String> fetchDescription(String serviceUrl) {
        for (String path : RequestImports.OPENAPI_PATHS) {
            HttpSender.Outcome o = sender.send(new HttpCall("GET", serviceUrl + path, List.of(), Optional.empty()));
            if (o instanceof HttpSender.Outcome.Answered a
                    && a.status() == 200
                    && (a.body().contains("\"openapi\"") || a.body().contains("\"swagger\""))) {
                return Optional.of(a.body());
            }
        }
        return Optional.empty();
    }

    EngineSession.Answer importPostman(Map<?, ?> body) {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return noProject();
        }
        try {
            Optional<String> text = fileText(body);
            if (text.isEmpty()) {
                return new EngineSession.Answer(
                        400, ApiJson.error("send {\"content\": \"...the collection file...\"} or {\"path\": \"...\"}"));
            }
            return saveImported(current.get().project(), RequestImports.postman(text.get()), "Postman");
        } catch (IllegalArgumentException e) {
            return new EngineSession.Answer(422, ApiJson.error(e.getMessage()));
        }
    }

    private synchronized EngineSession.Answer saveImported(
            ProjectSpec project, List<RequestImports.Imported> imported, String what) {
        RequestBook book = books.apply(project);
        List<SavedRequest> saved = imported.stream()
                .map(i -> new SavedRequest(newId(), i.name(), i.collection(), i.call()))
                .toList();
        Book next = book.load().withAll(saved);
        book.store(next);
        Map<String, Object> view = view(project, next);
        view.put("imported", saved.size());
        view.put("from", what);
        return new EngineSession.Answer(200, view);
    }

    /** A pasted curl command, as a request the app opens (not saved until the user saves it). */
    static EngineSession.Answer fromCurl(Map<?, ?> body) {
        if (!(body.get("command") instanceof String command)) {
            return new EngineSession.Answer(400, ApiJson.error("send {\"command\": \"curl ...\"}"));
        }
        try {
            return new EngineSession.Answer(200, HttpCallJson.write(Curl.parse(command)));
        } catch (IllegalArgumentException e) {
            return new EngineSession.Answer(422, ApiJson.error(e.getMessage()));
        }
    }

    /** "Copy as curl": with the variables filled in, so it runs as is in a terminal. */
    synchronized EngineSession.Answer toCurl(Map<?, ?> body) {
        Optional<HttpCall> call = HttpCallJson.read(body);
        if (call.isEmpty()) {
            return new EngineSession.Answer(400, ApiJson.error(CALL_SHAPE));
        }
        return withProject(p -> {
            RequestVariables.Resolved r = RequestVariables.resolve(
                    call.get(), variables(p, books.apply(p).load()));
            return ordered("curl", Curl.of(r.call()), "missing", List.copyOf(r.missing()));
        });
    }

    /**
     * Asks a GraphQL endpoint for its schema (the request's URL, headers and auth, with the standard introspection
     * query as body) and lists its queries and mutations, each with an example the app can paste.
     */
    EngineSession.Answer graphQlSchema(Map<?, ?> body) {
        Optional<HttpCall> typed = HttpCallJson.read(body);
        if (typed.isEmpty()) {
            return new EngineSession.Answer(400, ApiJson.error(CALL_SHAPE));
        }
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return noProject();
        }
        HttpCall t = typed.get();
        HttpCall ask = new HttpCall(
                "POST", t.url(), t.headers(), new HttpCall.Body.GraphQl(GraphQl.INTROSPECTION, ""), t.auth());
        Map<String, String> vars;
        synchronized (this) {
            vars = variables(
                    current.get().project(),
                    books.apply(current.get().project()).load());
        }
        HttpSender.Outcome o = sender.send(RequestVariables.resolve(ask, vars).call());
        if (!(o instanceof HttpSender.Outcome.Answered a)) {
            HttpSender.Outcome.Failed f = (HttpSender.Outcome.Failed) o;
            return new EngineSession.Answer(
                    422,
                    ApiJson.error(RequestTrouble.explain(
                            f,
                            RequestVariables.resolve(ask, vars).call().url(),
                            current.get().project(),
                            current.get().states())));
        }
        try {
            List<Map<String, Object>> ops = GraphQl.operations(a.body()).stream()
                    .map(op -> ordered(
                            "kind",
                            op.kind(),
                            "name",
                            op.name(),
                            "args",
                            op.args(),
                            "returns",
                            op.returns(),
                            "example",
                            op.example()))
                    .toList();
            return new EngineSession.Answer(200, Map.of("operations", ops));
        } catch (IllegalArgumentException e) {
            return new EngineSession.Answer(422, ApiJson.error(e.getMessage() + " (HTTP " + a.status() + ")"));
        }
    }

    /** Opens a WebSocket for the request (its URL, headers and auth), with the variables filled in. */
    EngineSession.Answer openSocket(Map<?, ?> body, SocketSessions sockets) {
        Optional<HttpCall> typed = HttpCallJson.read(body);
        if (typed.isEmpty()) {
            return new EngineSession.Answer(400, ApiJson.error(CALL_SHAPE));
        }
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return noProject();
        }
        RequestVariables.Resolved r;
        synchronized (this) {
            r = RequestVariables.resolve(
                    typed.get(),
                    variables(
                            current.get().project(),
                            books.apply(current.get().project()).load()));
        }
        if (!r.missing().isEmpty()) {
            return new EngineSession.Answer(
                    422,
                    ApiJson.error(
                            "{{" + String.join("}}, {{", r.missing()) + "}} has no value: set it under Variables"));
        }
        String url = r.call().url();
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            return new EngineSession.Answer(400, ApiJson.error("a WebSocket address starts with ws:// or wss://"));
        }
        return new EngineSession.Answer(200, sockets.open(r.call()));
    }

    /** A request a crash line in the logs points at, as it was typed. */
    EngineSession.Answer sent(long id) {
        HttpCall call = sentCalls.get(id);
        return call == null
                ? new EngineSession.Answer(404, ApiJson.error("that request is too old to open again"))
                : new EngineSession.Answer(200, HttpCallJson.write(call));
    }

    // ---- views ----

    private static Map<String, String> variables(ProjectSpec project, Book book) {
        Map<String, String> vars = new LinkedHashMap<>(RequestVariables.ofServices(project));
        vars.putAll(book.variables()); // the user's win
        return vars;
    }

    private Map<String, Object> view(ProjectSpec project, Book book) {
        List<Map<String, Object>> vars = new ArrayList<>();
        RequestVariables.ofServices(project)
                .forEach((k, v) -> vars.add(ordered("name", k, "value", v, "source", "service", "secret", false)));
        book.variables()
                .forEach((k, v) -> vars.add(
                        ordered("name", k, "value", v, "source", "user", "secret", ServiceEnvironment.looksSecret(k))));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("variables", vars);
        m.put("shared", List.copyOf(books.apply(project).sharedCollections()));
        m.put(
                "saved",
                book.saved().stream()
                        .map(r -> {
                            Map<String, Object> x =
                                    ordered("id", r.id(), "name", r.name(), "collection", r.collection());
                            x.putAll(HttpCallJson.write(r.call()));
                            x.put("checks", HttpCallJson.writeChecks(r.checks()));
                            x.put("captures", HttpCallJson.writeCaptures(r.captures()));
                            return x;
                        })
                        .toList());
        m.put(
                "history",
                book.history().stream()
                        .map(h -> {
                            Map<String, Object> x = ordered("at", h.at().toString());
                            x.putAll(HttpCallJson.write(h.call()));
                            x.put("status", h.status().orElse(null));
                            x.put("ms", h.took().toMillis());
                            return x;
                        })
                        .toList());
        // Values of cookies are shown masked: a session cookie is as good as a password.
        m.put(
                "cookies",
                sender.cookies().stream()
                        .map(c -> ordered(
                                "domain", c.domain(),
                                "path", c.path(),
                                "name", c.name(),
                                "value", c.value().length() > 6 ? c.value().substring(0, 4) + "…" : "••••",
                                "httpOnly", c.httpOnly()))
                        .toList());
        return m;
    }

    private static List<Map<String, Object>> headers(List<HttpCall.Header> headers) {
        return headers.stream()
                .map(h -> ordered("name", h.name(), "value", h.value()))
                .toList();
    }

    private static Duration durationOf(HttpSender.Outcome o) {
        return switch (o) {
            case HttpSender.Outcome.Answered a -> a.took();
            case HttpSender.Outcome.Failed f -> f.took();
        };
    }

    private EngineSession.Answer withProject(Function<ProjectSpec, Map<String, Object>> call) {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return noProject();
        }
        try {
            return new EngineSession.Answer(200, call.apply(current.get().project()));
        } catch (IllegalStateException e) {
            return new EngineSession.Answer(422, ApiJson.error(e.getMessage())); // refused, with the reason
        }
    }

    private String newId() {
        byte[] b = new byte[6];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static EngineSession.Answer noProject() {
        return new EngineSession.Answer(404, ApiJson.error("no project is open"));
    }

    private static Map<String, Object> ordered(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }
}
