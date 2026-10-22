package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.FakeRows;
import io.github.louch.devdesk.core.data.KeyDumpFile;
import io.github.louch.devdesk.core.data.KeyValueBrowser;
import io.github.louch.devdesk.core.data.ObjectStore;
import io.github.louch.devdesk.core.data.SavedQueries;
import io.github.louch.devdesk.core.data.SavedQueries.SavedQuery;
import io.github.louch.devdesk.core.data.SearchIndex;
import io.github.louch.devdesk.core.data.SlowQueries;
import io.github.louch.devdesk.core.data.SqlBrowser;
import io.github.louch.devdesk.core.data.SqlDialect;
import io.github.louch.devdesk.core.data.SqlEditor;
import io.github.louch.devdesk.core.data.TableDesign;
import io.github.louch.devdesk.core.data.TableFile;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * The Data page's file work: export and import (CSV, JSON, SQL), whole-database backups, and saved queries. Each
 * call goes through {@link EngineSession#data}, so it only ever reaches a database of the open project.
 */
final class DataWorkbench {

    /** Bigger imports belong in the database's own tools (psql, mysql). */
    static final long MAX_IMPORT_BYTES = 200L * 1024 * 1024;
    /** An export without a file (the browser fallback) comes back in the answer, so it must stay small. */
    static final long MAX_INLINE_EXPORT = 50L * 1024 * 1024;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final EngineSession session;
    private final Path backupsRoot;
    private final Function<ProjectSpec, SavedQueries> queries;
    private final Clock clock;
    private final RandomGenerator random;

    DataWorkbench(
            EngineSession session,
            Path backupsRoot,
            Function<ProjectSpec, SavedQueries> queries,
            Clock clock,
            RandomGenerator random) {
        this.session = session;
        this.backupsRoot = backupsRoot;
        this.queries = queries;
        this.clock = clock;
        this.random = random;
    }

    // ---- export and import ----

    /**
     * {format, schema, table, filters?, orderBy?, descending?} exports a table (the filters the grid shows), or
     * {format, sql} a query's result. With {path} the engine writes the file; without, the text comes back.
     */
    EngineSession.Answer export(String service, Map<?, ?> body) {
        Optional<TableFile.Format> format = format(body.get("format"));
        Optional<String> path = text(body.get("path"));
        Optional<String> sql = text(body.get("sql"));
        Optional<SqlBrowser.RowQuery> table = sql.isPresent() ? Optional.empty() : ApiServer.rowQuery(body);
        if (format.isEmpty() || (sql.isEmpty() && table.isEmpty())) {
            return bad("send {\"format\": \"csv\" | \"json\" | \"sql\", \"schema\", \"table\", \"filters\"?} or"
                    + " {\"format\", \"sql\": \"select ...\"}, and \"path\"? to write a file");
        }
        if (path.isPresent() && !Path.of(path.get()).isAbsolute()) {
            return bad("the path must be absolute: " + path.get());
        }
        return session.data(service, true, t -> {
            String schema = table.map(SqlBrowser.RowQuery::schema).orElse("");
            String name = table.map(SqlBrowser.RowQuery::table).orElse("query");
            StringWriter inline = new StringWriter();
            long rows;
            try (Writer out = path.isPresent()
                    ? Files.newBufferedWriter(Path.of(path.get()), StandardCharsets.UTF_8)
                    : new LimitedWriter(inline, MAX_INLINE_EXPORT)) {
                TableFile.Writing w = TableFile.writer(format.get(), out, SqlDialect.of(t.kind()), schema, name);
                if (sql.isPresent()) {
                    session.sql(t).each(t, sql.get(), w);
                } else {
                    session.sql(t).each(t, table.get(), w);
                }
                rows = w.finish();
            } catch (IOException | UncheckedIOException e) {
                throw new DataException("couldn't write " + path.orElse("the export") + ": " + e.getMessage(), e);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("rows", rows);
            m.put("fileName", name + "." + format.get().extension());
            path.ifPresent(p -> m.put("path", p));
            if (path.isEmpty()) {
                m.put("content", inline.toString());
            }
            return m;
        });
    }

    /**
     * {format, schema, table, path | content}: CSV and JSON rows are inserted into the table, all in one transaction;
     * a SQL file runs as one script (schema and table aren't needed).
     */
    EngineSession.Answer importFile(String service, Map<?, ?> body) {
        Optional<TableFile.Format> format = format(body.get("format"));
        Optional<String> path = text(body.get("path"));
        Optional<String> content = body.get("content") instanceof String c ? Optional.of(c) : Optional.empty();
        if (format.isEmpty() || (path.isEmpty() && content.isEmpty())) {
            return bad(
                    "send {\"format\": \"csv\" | \"json\" | \"sql\", \"schema\", \"table\", \"path\" or \"content\"}");
        }
        Optional<String> schema = text(body.get("schema"));
        Optional<String> table = text(body.get("table"));
        if (format.get() != TableFile.Format.SQL && table.isEmpty()) {
            return bad("a CSV or JSON import needs the table to insert into: send \"schema\" and \"table\"");
        }
        return session.data(service, true, t -> {
            String text = content.orElseGet(() -> read(Path.of(path.get())));
            SqlEditor.Applied applied = switch (format.get()) {
                case SQL -> session.sqlEditor(t).runScript(t, text);
                case CSV, JSON -> {
                    List<Map<String, Optional<String>>> rows =
                            format.get() == TableFile.Format.CSV ? TableFile.readCsv(text) : TableFile.readJson(text);
                    if (rows.isEmpty()) {
                        throw new DataException("the file has no rows to import");
                    }
                    List<SqlEditor.RowChange> inserts = new ArrayList<>();
                    rows.forEach(r -> inserts.add(new SqlEditor.RowChange.Insert(r)));
                    yield session.sqlEditor(t)
                            .apply(t, new SqlEditor.TableChanges(schema.orElse(""), table.get(), inserts));
                }
            };
            return ApiJson.applied(applied);
        });
    }

    private static String read(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                throw new DataException("there is no file " + file);
            }
            if (Files.size(file) > MAX_IMPORT_BYTES) {
                throw new DataException(file + " is bigger than " + MAX_IMPORT_BYTES / 1024 / 1024
                        + " MB: use the database's own tool (psql, mysql) for files this size");
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DataException("couldn't read " + file + ": " + e.getMessage(), e);
        }
    }

    // ---- Redis keys to a file and back ----

    /** At most this many keys go in one export file. */
    static final int MAX_EXPORT_KEYS = 100_000;

    /** {pattern, path?}: the matching keys as DevDesk's key file, written to {path} or sent back. */
    EngineSession.Answer exportKeys(String service, Map<?, ?> body) {
        String pattern = text(body.get("pattern")).orElse("*");
        Optional<String> path = text(body.get("path"));
        return session.data(service, false, t -> {
            List<KeyValueBrowser.KeyDump> keys = session.redis().dump(t, pattern, MAX_EXPORT_KEYS);
            String file = KeyDumpFile.write(keys);
            if (path.isPresent()) {
                try {
                    Files.writeString(Path.of(path.get()), file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new DataException("couldn't write " + path.get() + ": " + e.getMessage(), e);
                }
                return Map.of("keys", keys.size(), "path", path.get());
            }
            if (file.length() > MAX_INLINE_EXPORT) {
                throw new DataException("the export is bigger than 50 MB: pick a file to save it to");
            }
            return Map.of("keys", keys.size(), "content", file);
        });
    }

    /** {path or content, replace}: puts the keys of a DevDesk key file back. */
    EngineSession.Answer importKeys(String service, Map<?, ?> body) {
        Optional<String> path = text(body.get("path"));
        Optional<String> content = body.get("content") instanceof String c ? Optional.of(c) : Optional.empty();
        if (path.isEmpty() && content.isEmpty()) {
            return bad("send {\"path\" or \"content\", \"replace\": true|false}");
        }
        boolean replace = Boolean.TRUE.equals(body.get("replace"));
        return session.data(service, false, t -> {
            List<KeyValueBrowser.KeyDump> keys = KeyDumpFile.read(content.orElseGet(() -> read(Path.of(path.get()))));
            long written = session.redis().restore(t, keys, replace);
            return Map.of("keys", keys.size(), "written", written, "skipped", keys.size() - written);
        });
    }

    // ---- create and change tables ----

    static final String DESIGN_SHAPE = "send {\"table\": current name or \"\" for a new table, \"design\": {\"schema\","
            + " \"name\", \"columns\": [{\"was\"?, \"name\", \"type\", \"nullable\", \"default\"?, \"primaryKey\"}],"
            + " \"indexes\": [{\"was\"?, \"name\", \"columns\", \"unique\"}], \"links\": [{\"was\"?, \"name\","
            + " \"columns\", \"toSchema\", \"toTable\", \"toColumns\"}]}}";

    /** The SQL a table form would run, for the user to read first. Nothing runs. */
    EngineSession.Answer previewDesign(String service, Map<?, ?> body) {
        Optional<TableDesign.Design> design = design(body.get("design"));
        if (design.isEmpty() || !(body.get("table") instanceof String table)) {
            return bad(DESIGN_SHAPE);
        }
        return session.data(service, true, t -> {
            List<String> sql = designSql(t, table, design.get());
            List<String> notes = new ArrayList<>();
            if (t.kind() == DatabaseTarget.Kind.MYSQL && sql.size() > 1) {
                notes.add("MySQL applies each of these statements on its own: if one fails, the ones before it stay"
                        + " done.");
            }
            return Map.of("sql", sql, "notes", notes);
        });
    }

    /**
     * Runs the form's SQL, but only if it is still exactly what the user saw ({"sql": [...]}): the table may have
     * changed since the preview. Postgres and SQLite run it in one transaction.
     */
    EngineSession.Answer applyDesign(String service, Map<?, ?> body) {
        Optional<TableDesign.Design> design = design(body.get("design"));
        if (design.isEmpty()
                || !(body.get("table") instanceof String table)
                || !(body.get("sql") instanceof List<?> seen)) {
            return bad(DESIGN_SHAPE + " plus the \"sql\" the preview showed");
        }
        return session.data(service, true, t -> {
            List<String> sql = designSql(t, table, design.get());
            if (!sql.equals(seen)) {
                throw new DataException("the table changed since the preview: check the SQL again before running it");
            }
            if (sql.isEmpty()) {
                throw new DataException("nothing to change");
            }
            return ApiJson.applied(session.sqlEditor(t).runScript(t, String.join(";\n", sql) + ";"));
        });
    }

    /** {"schema", "table", "confirm": the table's name typed again}: DROP TABLE. */
    EngineSession.Answer dropTable(String service, Map<?, ?> body) {
        Optional<String> schema = Optional.ofNullable(body.get("schema") instanceof String x ? x : null);
        Optional<String> table = text(body.get("table"));
        if (schema.isEmpty() || table.isEmpty() || !table.equals(text(body.get("confirm")))) {
            return bad("send {\"schema\", \"table\", \"confirm\": the table's name again}");
        }
        return session.data(
                service,
                true,
                t -> ApiJson.applied(session.sqlEditor(t)
                        .runScript(t, TableDesign.drop(SqlDialect.of(t.kind()), schema.get(), table.get()) + ";")));
    }

    private List<String> designSql(DatabaseTarget t, String table, TableDesign.Design design) {
        Optional<SqlBrowser.Structure> now =
                table.isBlank() ? Optional.empty() : session.sql(t).structure(t, design.schema(), table);
        if (!table.isBlank() && now.isEmpty()) {
            throw new DataException("there is no table " + design.schema() + "." + table + " any more");
        }
        if (table.isBlank()
                && session.sql(t).structure(t, design.schema(), design.name()).isPresent()) {
            throw new DataException("a table called " + design.name() + " already exists");
        }
        return TableDesign.sql(SqlDialect.of(t.kind()), table.isBlank() ? design.name() : table, now, design);
    }

    /** Validates the form's JSON (API input is a boundary). */
    static Optional<TableDesign.Design> design(Object o) {
        if (!(o instanceof Map<?, ?> m)
                || !(m.get("schema") instanceof String schema)
                || !(m.get("name") instanceof String name)
                || !(m.get("columns") instanceof List<?> cols)) {
            return Optional.empty();
        }
        List<TableDesign.Column> columns = new ArrayList<>();
        for (Object c : cols) {
            if (!(c instanceof Map<?, ?> col)
                    || !(col.get("name") instanceof String n)
                    || !(col.get("type") instanceof String type)) {
                return Optional.empty();
            }
            columns.add(new TableDesign.Column(
                    text(col.get("was")),
                    n.strip(),
                    type.strip(),
                    !Boolean.FALSE.equals(col.get("nullable")),
                    text(col.get("default")),
                    Boolean.TRUE.equals(col.get("primaryKey"))));
        }
        List<TableDesign.Index> indexes = new ArrayList<>();
        for (Object i : m.get("indexes") instanceof List<?> l ? l : List.of()) {
            if (!(i instanceof Map<?, ?> x) || !(x.get("name") instanceof String n)) {
                return Optional.empty();
            }
            indexes.add(new TableDesign.Index(
                    text(x.get("was")), n.strip(), strings(x.get("columns")), Boolean.TRUE.equals(x.get("unique"))));
        }
        List<TableDesign.Link> links = new ArrayList<>();
        for (Object i : m.get("links") instanceof List<?> l ? l : List.of()) {
            if (!(i instanceof Map<?, ?> x)
                    || !(x.get("name") instanceof String n)
                    || !(x.get("toTable") instanceof String to)) {
                return Optional.empty();
            }
            links.add(new TableDesign.Link(
                    text(x.get("was")),
                    n.strip(),
                    strings(x.get("columns")),
                    x.get("toSchema") instanceof String ts ? ts : schema,
                    to,
                    strings(x.get("toColumns"))));
        }
        return Optional.of(new TableDesign.Design(schema, name.strip(), columns, indexes, links));
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            l.forEach(x -> {
                if (x instanceof String s && !s.isBlank()) {
                    out.add(s);
                }
            });
        }
        return out;
    }

    // ---- S3-style file storage (MinIO, RustFS) ----

    private static final Set<DatabaseTarget.Kind> OBJECTS = EnumSet.of(DatabaseTarget.Kind.OBJECTS);
    /** A preview shows the start of a file: enough to read a JSON or see an image, never a whole video. */
    static final int PREVIEW_BYTES = 256 * 1024;
    /** Downloads and uploads go through the engine's memory: big enough for test files, not for backups. */
    static final int MAX_TRANSFER = 200 * 1024 * 1024;

    EngineSession.Answer buckets(String service) {
        return session.data(
                service,
                OBJECTS,
                "S3 storage",
                t -> Map.of(
                        "buckets",
                        session.objects().buckets(t).stream()
                                .map(b -> Map.of(
                                        "name", b.name(), "created", b.created().toString()))
                                .toList()));
    }

    EngineSession.Answer createBucket(String service, Map<?, ?> body) {
        Optional<String> name = text(body.get("name"));
        if (name.isEmpty()) {
            return bad("send {\"name\": \"invoices\"}");
        }
        return session.data(service, OBJECTS, "S3 storage", t -> {
            session.objects().createBucket(t, name.get());
            return Map.of("created", name.get());
        });
    }

    /** One folder level of a bucket: its sub-folders and files, 500 at a time. */
    EngineSession.Answer files(String service, String bucket, String prefix, Optional<String> after) {
        return session.data(service, OBJECTS, "S3 storage", t -> {
            ObjectStore.Listing l = session.objects().list(t, bucket, prefix, after);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("folders", l.folders());
            m.put(
                    "files",
                    l.files().stream()
                            .map(f -> Map.of(
                                    "key",
                                    f.key(),
                                    "size",
                                    f.size(),
                                    "modified",
                                    f.modified().toString()))
                            .toList());
            m.put("next", l.next().orElse(null));
            return m;
        });
    }

    /** The start of a file: as text when it reads as text, as an image when it is one, else just its size and type. */
    EngineSession.Answer preview(String service, String bucket, String key) {
        return session.data(service, OBJECTS, "S3 storage", t -> {
            ObjectStore.Content c = session.objects().read(t, bucket, key, PREVIEW_BYTES);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("contentType", c.contentType());
            m.put("size", c.size());
            m.put("truncated", c.truncated());
            boolean image = c.contentType().startsWith("image/") && !c.truncated();
            m.put("image", image ? java.util.Base64.getEncoder().encodeToString(c.bytes()) : null);
            m.put("text", !image && looksLikeText(c.bytes()) ? new String(c.bytes(), StandardCharsets.UTF_8) : null);
            return m;
        });
    }

    /** {bucket, key, path}: saves the whole file to the user's disk. */
    EngineSession.Answer download(String service, Map<?, ?> body) {
        Optional<String> bucket = text(body.get("bucket"));
        Optional<String> key = text(body.get("key"));
        Optional<String> path = text(body.get("path"));
        if (bucket.isEmpty() || key.isEmpty() || path.isEmpty()) {
            return bad("send {\"bucket\", \"key\", \"path\"}");
        }
        return session.data(service, OBJECTS, "S3 storage", t -> {
            ObjectStore.Content c = session.objects().read(t, bucket.get(), key.get(), MAX_TRANSFER);
            if (c.truncated()) {
                throw new DataException("the file is bigger than " + MAX_TRANSFER / 1024 / 1024 + " MB: use the mc"
                        + " or aws command line for it");
            }
            try {
                Files.write(Path.of(path.get()), c.bytes());
            } catch (IOException e) {
                throw new DataException("couldn't write " + path.get() + ": " + e.getMessage(), e);
            }
            return Map.of("saved", path.get(), "bytes", c.bytes().length);
        });
    }

    /** {bucket, key, path}: uploads a file from the user's disk ({key} ends with "/" = keep the file's name). */
    EngineSession.Answer upload(String service, Map<?, ?> body) {
        Optional<String> bucket = text(body.get("bucket"));
        Optional<String> path = text(body.get("path"));
        if (bucket.isEmpty() || path.isEmpty()) {
            return bad("send {\"bucket\", \"key\", \"path\"}");
        }
        Path file = Path.of(path.get());
        String key = text(body.get("key")).orElse("");
        String fullKey = key.isEmpty() || key.endsWith("/") ? key + file.getFileName() : key;
        return session.data(service, OBJECTS, "S3 storage", t -> {
            byte[] bytes;
            String type;
            try {
                if (Files.size(file) > MAX_TRANSFER) {
                    throw new DataException(file + " is bigger than " + MAX_TRANSFER / 1024 / 1024 + " MB");
                }
                bytes = Files.readAllBytes(file);
                type = Optional.ofNullable(Files.probeContentType(file)).orElse("application/octet-stream");
            } catch (IOException e) {
                throw new DataException("couldn't read " + file + ": " + e.getMessage(), e);
            }
            session.objects().write(t, bucket.get(), fullKey, bytes, type);
            return Map.of("key", fullKey, "bytes", bytes.length);
        });
    }

    EngineSession.Answer deleteFile(String service, Map<?, ?> body) {
        Optional<String> bucket = text(body.get("bucket"));
        Optional<String> key = text(body.get("key"));
        if (bucket.isEmpty() || key.isEmpty()) {
            return bad("send {\"bucket\", \"key\"}");
        }
        return session.data(service, OBJECTS, "S3 storage", t -> {
            session.objects().delete(t, bucket.get(), key.get());
            return Map.of("deleted", key.get());
        });
    }

    /** No zero bytes and valid UTF-8 at the start: shown as text. A cut in the middle of a character is fine. */
    static boolean looksLikeText(byte[] bytes) {
        int n = Math.min(bytes.length, 8192);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                return false;
            }
        }
        var decoder = StandardCharsets.UTF_8.newDecoder();
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes, 0, Math.max(0, n - 4)));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    // ---- Elasticsearch / OpenSearch ----

    private static final Set<DatabaseTarget.Kind> SEARCH = EnumSet.of(DatabaseTarget.Kind.SEARCH);

    EngineSession.Answer indices(String service) {
        return session.data(
                service,
                SEARCH,
                "search engine",
                t -> Map.of(
                        "indices",
                        session.search().indices(t).stream()
                                .map(i -> Map.of(
                                        "name",
                                        i.name(),
                                        "documents",
                                        i.documents(),
                                        "bytes",
                                        i.bytes(),
                                        "health",
                                        i.health()))
                                .toList()));
    }

    EngineSession.Answer mapping(String service, String index) {
        return session.data(
                service,
                SEARCH,
                "search engine",
                t -> Map.of("mapping", session.search().mapping(t, index)));
    }

    /** {index, query, from?, size?}: plain words or field:value, or the engine's own JSON. */
    EngineSession.Answer search(String service, Map<?, ?> body) {
        Optional<String> index = text(body.get("index"));
        if (index.isEmpty()) {
            return bad("send {\"index\", \"query\"}");
        }
        String query = body.get("query") instanceof String q ? q : "";
        int from = body.get("from") instanceof Number n ? Math.max(0, n.intValue()) : 0;
        int size = body.get("size") instanceof Number n ? Math.clamp(n.intValue(), 1, 200) : 20;
        return session.data(service, SEARCH, "search engine", t -> {
            SearchIndex.Hits h = session.search().search(t, index.get(), query, from, size);
            return Map.of(
                    "total", h.total(),
                    "exact", h.exact(),
                    "tookMs", h.tookMs(),
                    "hits",
                            h.hits().stream()
                                    .map(x -> Map.of("id", x.id(), "score", x.score(), "source", x.source()))
                                    .toList());
        });
    }

    // ---- slowest queries (Postgres's pg_stat_statements) ----

    EngineSession.Answer slowQueries(String service) {
        return session.data(service, EnumSet.of(DatabaseTarget.Kind.POSTGRES), "Postgres", t -> {
            SlowQueries s = session.sql(t).slowQueries(t);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", s.status().name().toLowerCase());
            m.put("since", s.since().map(Object::toString).orElse(null));
            if (s.status() == SlowQueries.Status.NOT_LOADED) {
                Optional<String> yaml = loadNeedsYaml(service);
                m.put("load", yaml.isPresent() ? "yaml" : "restart");
                m.put("yaml", yaml.orElse(null));
            }
            m.put(
                    "statements",
                    s.statements().stream()
                            .map(x -> {
                                Map<String, Object> st = new LinkedHashMap<>();
                                st.put("query", x.query());
                                st.put("calls", x.calls());
                                st.put("totalMs", x.totalMs());
                                st.put("meanMs", x.meanMs());
                                st.put("maxMs", x.maxMs());
                                st.put("rows", x.rows());
                                st.put("shape", x.shape());
                                return st;
                            })
                            .toList());
            return m;
        });
    }

    /**
     * The library is read only when Postgres starts, and DevDesk recreates a container on restart. A setting saved
     * with ALTER SYSTEM lives in the data folder: it survives only if that folder is on a volume. Otherwise the way is
     * Postgres's own command line, in devdesk.yaml: returned here, ready to paste.
     */
    private Optional<String> loadNeedsYaml(String service) {
        Optional<ServiceSpec> spec =
                session.current().map(c -> c.project().services().get(service));
        if (spec.isEmpty() || !(spec.get().workload() instanceof Workload.Container c)) {
            return Optional.empty();
        }
        boolean kept = c.volumes().stream().anyMatch(v -> v.contains(":/var/lib/postgresql"));
        if (kept) {
            return Optional.empty();
        }
        String command = c.command().orElse("postgres") + " -c shared_preload_libraries=pg_stat_statements";
        return Optional.of("services:\n  " + service + ":\n    container:\n      command: " + command);
    }

    /**
     * {step: load | create | reset}: one explicit step. "load" changes a server setting that's only read at start,
     * so the database service is restarted right after (the user was told before clicking).
     */
    EngineSession.Answer slowQueriesStep(String service, Map<?, ?> body) {
        SqlEditor.SlowQueriesStep step;
        try {
            step = SqlEditor.SlowQueriesStep.valueOf(
                    String.valueOf(body.get("step")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return bad("send {\"step\": \"load\", \"create\" or \"reset\"}");
        }
        if (step == SqlEditor.SlowQueriesStep.LOAD && loadNeedsYaml(service).isPresent()) {
            return new EngineSession.Answer(
                    409,
                    ApiJson.error(service + " keeps no data between starts, so a setting changed now would be gone"
                            + " after the restart. Add the command line shown to devdesk.yaml instead."));
        }
        EngineSession.Answer done = session.data(service, EnumSet.of(DatabaseTarget.Kind.POSTGRES), "Postgres", t -> {
            session.sqlEditor(t).slowQueries(t, step);
            return Map.of("done", step.name().toLowerCase());
        });
        if (step == SqlEditor.SlowQueriesStep.LOAD && done.status() < 300) {
            EngineSession.Answer restarted = session.serviceAction(service, "restart");
            return new EngineSession.Answer(200, Map.of("done", "load", "restarting", restarted.status() < 300));
        }
        return done;
    }

    // ---- fake test rows ----

    /** At most this many made-up rows in one go: enough to test paging and search, small enough to stay quick. */
    static final int MAX_FAKE_ROWS = 10_000;

    /** {schema, table}: how each column would be filled, a few example rows, and what stops it, if anything. */
    EngineSession.Answer fakePreview(String service, Map<?, ?> body) {
        if (!(body.get("schema") instanceof String schema) || !(body.get("table") instanceof String table)) {
            return bad("send {\"schema\", \"table\"}");
        }
        return session.data(service, true, t -> {
            FakeRows fake = fakeRows(t, schema, table);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(
                    "plan",
                    fake.plan().stream()
                            .map(f -> Map.of("column", f.column(), "how", f.how(), "generated", f.generated()))
                            .toList());
            m.put("examples", fake.problem().isPresent() ? List.of() : cells(fake.rows(5)));
            m.put("problem", fake.problem().orElse(null));
            return m;
        });
    }

    /** {schema, table, count}: inserts that many made-up rows, all in one transaction. */
    EngineSession.Answer fakeInsert(String service, Map<?, ?> body) {
        if (!(body.get("schema") instanceof String schema)
                || !(body.get("table") instanceof String table)
                || !(body.get("count") instanceof Number n)) {
            return bad("send {\"schema\", \"table\", \"count\": 1 to " + MAX_FAKE_ROWS + "}");
        }
        int count = Math.clamp(n.intValue(), 1, MAX_FAKE_ROWS);
        return session.data(service, true, t -> {
            FakeRows fake = fakeRows(t, schema, table);
            if (fake.problem().isPresent()) {
                throw new DataException(fake.problem().get());
            }
            List<SqlEditor.RowChange> inserts = fake.rows(count).stream()
                    .map(r -> (SqlEditor.RowChange) new SqlEditor.RowChange.Insert(r))
                    .toList();
            SqlEditor.Applied applied =
                    session.sqlEditor(t).apply(t, new SqlEditor.TableChanges(schema, table, inserts));
            return Map.of("inserted", applied.rows(), "ms", applied.took().toMillis());
        });
    }

    /**
     * Reads what the generator needs: the table's columns, some existing values for each link to another table, and
     * the largest plain integer id (so new ids follow it).
     */
    private FakeRows fakeRows(DatabaseTarget t, String schema, String table) {
        SqlBrowser browser = session.sql(t);
        SqlBrowser.Structure structure = browser.structure(t, schema, table)
                .orElseThrow(() -> new DataException("there's no table " + schema + "." + table));
        SqlDialect d = SqlDialect.of(t.kind());
        Map<String, List<String>> references = new LinkedHashMap<>();
        for (SqlBrowser.ForeignKey f : structure.foreignKeys()) {
            if (f.columns().size() != 1) {
                continue;
            }
            String to = d.name(f.toColumns().getFirst());
            var existing = browser.run(
                    t,
                    "select distinct " + to + " from " + d.table(f.toSchema(), f.toTable()) + " where " + to
                            + " is not null",
                    1000);
            references.put(
                    f.columns().getFirst(),
                    existing.rows().stream().map(List::getFirst).toList());
        }
        Map<String, Long> maxIds = new LinkedHashMap<>();
        List<SqlBrowser.Column> keys = structure.columns().stream()
                .filter(SqlBrowser.Column::primaryKey)
                .toList();
        if (keys.size() == 1 && keys.getFirst().defaultValue().isEmpty()) {
            try {
                var max = browser.run(
                        t, "select max(" + d.name(keys.getFirst().name()) + ") from " + d.table(schema, table), 1);
                String v = max.rows().isEmpty() ? null : max.rows().getFirst().getFirst();
                if (v != null) {
                    maxIds.put(keys.getFirst().name(), Long.parseLong(v.strip()));
                }
            } catch (NumberFormatException e) {
                // not a number after all (a text key): the generator won't count from it either
            }
        }
        return new FakeRows(new FakeRows.Input(structure, t.kind(), references, maxIds), random, clock);
    }

    /** Rows for the app: SQL NULL stays null, so it shows differently from an empty text. */
    private static List<Map<String, String>> cells(List<Map<String, Optional<String>>> rows) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, Optional<String>> r : rows) {
            Map<String, String> m = new LinkedHashMap<>();
            r.forEach((k, v) -> m.put(k, v.orElse(null)));
            out.add(m);
        }
        return out;
    }

    // ---- backups: $XDG_STATE_HOME/devdesk/backups/<project-key>/<service>/<time>[_label].<sql|db> ----

    /** Databases whose whole content DevDesk can save and restore. */
    static final Set<DatabaseTarget.Kind> BACKED_UP = EnumSet.of(
            DatabaseTarget.Kind.POSTGRES,
            DatabaseTarget.Kind.MYSQL,
            DatabaseTarget.Kind.SQLITE,
            DatabaseTarget.Kind.MONGO);

    EngineSession.Answer backups(String service) {
        return withProject(p ->
                session.data(service, BACKED_UP, "SQL or MongoDB", t -> Map.of("backups", list(folder(p, t)))));
    }

    /** {label?}: saves the whole database now. */
    EngineSession.Answer saveBackup(String service, Map<?, ?> body) {
        String label = text(body.get("label"))
                .map(l ->
                        l.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""))
                .filter(l -> !l.isEmpty())
                .map(l -> "_" + (l.length() > 40 ? l.substring(0, 40) : l))
                .orElse("");
        return withProject(p -> session.data(service, BACKED_UP, "SQL or MongoDB", t -> {
            Path dir = folder(p, t);
            String extension = switch (t.kind()) {
                case SQLITE -> ".db";
                case MONGO -> ".archive"; // mongodump's own format
                default -> ".sql";
            };
            Path file = dir.resolve(STAMP.format(clock.instant().atZone(ZoneId.systemDefault())) + label + extension);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new DataException("couldn't create the backups folder " + dir + ": " + e.getMessage(), e);
            }
            long started = System.nanoTime();
            session.backups(t).save(p.name(), t, file);
            Map<String, Object> m = new LinkedHashMap<>(entry(file));
            m.put("ms", (System.nanoTime() - started) / 1_000_000);
            return m;
        }));
    }

    EngineSession.Answer restoreBackup(String service, String name) {
        return withProject(p -> session.data(service, BACKED_UP, "SQL or MongoDB", t -> {
            Path file = existing(folder(p, t), name);
            long started = System.nanoTime();
            session.backups(t).restore(p.name(), t, file);
            return Map.of("restored", name, "ms", (System.nanoTime() - started) / 1_000_000);
        }));
    }

    EngineSession.Answer deleteBackup(String service, String name) {
        return withProject(p -> session.data(service, BACKED_UP, "SQL or MongoDB", t -> {
            Path file = existing(folder(p, t), name);
            try {
                Files.delete(file);
            } catch (IOException e) {
                throw new DataException("couldn't delete " + file + ": " + e.getMessage(), e);
            }
            return Map.of("backups", list(folder(p, t)));
        }));
    }

    private Path folder(ProjectSpec p, DatabaseTarget t) {
        // SQLite targets are named like "data:app.db": safe as a folder name.
        return backupsRoot.resolve(p.key()).resolve(t.service().replaceAll("[^A-Za-z0-9_.:-]", "_"));
    }

    /** A name from the app is only ever looked up among the folder's own files, never used as a path. */
    private static Path existing(Path dir, String name) {
        return listFiles(dir).stream()
                .filter(f -> f.getFileName().toString().equals(name))
                .findFirst()
                .orElseThrow(() -> new DataException("there is no backup called " + name));
    }

    private static List<Map<String, Object>> list(Path dir) {
        return listFiles(dir).stream()
                .sorted(Comparator.comparing((Path f) -> f.getFileName().toString())
                        .reversed())
                .map(DataWorkbench::entry)
                .toList();
    }

    private static List<Path> listFiles(Path dir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            files.forEach(f -> {
                if (Files.isRegularFile(f)) {
                    out.add(f);
                }
            });
        } catch (IOException e) {
            throw new DataException("couldn't read the backups folder " + dir + ": " + e.getMessage(), e);
        }
        return out;
    }

    private static Map<String, Object> entry(Path f) {
        try {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.getFileName().toString());
            m.put("path", f.toString());
            m.put("bytes", Files.size(f));
            m.put("at", Files.getLastModifiedTime(f).toInstant().toString());
            return m;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- saved queries ----

    synchronized EngineSession.Answer queries(String service) {
        return withProject(p -> new EngineSession.Answer(
                200, Map.of("queries", view(queries.apply(p).load(), service))));
    }

    /** {id?, name, sql}: a new query, or the one with this id changed. */
    synchronized EngineSession.Answer saveQuery(String service, Map<?, ?> body) {
        Optional<String> name = text(body.get("name"));
        Optional<String> sql = text(body.get("sql"));
        if (name.isEmpty() || sql.isEmpty()) {
            return bad("send {\"name\", \"sql\", \"id\"?}");
        }
        return withProject(p -> {
            SavedQueries store = queries.apply(p);
            String id = text(body.get("id")).orElseGet(this::newId);
            List<SavedQuery> next = new ArrayList<>(store.load());
            SavedQuery q = new SavedQuery(id, service, name.get().strip(), sql.get());
            int at = indexOf(next, id);
            if (at >= 0) {
                next.set(at, q);
            } else {
                next.add(q);
            }
            store.store(next);
            return new EngineSession.Answer(200, Map.of("id", id, "queries", view(next, service)));
        });
    }

    synchronized EngineSession.Answer deleteQuery(String service, String id) {
        return withProject(p -> {
            SavedQueries store = queries.apply(p);
            List<SavedQuery> next =
                    store.load().stream().filter(q -> !q.id().equals(id)).toList();
            store.store(next);
            return new EngineSession.Answer(200, Map.of("queries", view(next, service)));
        });
    }

    private static int indexOf(List<SavedQuery> list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private static List<Map<String, Object>> view(List<SavedQuery> all, String service) {
        return all.stream()
                .filter(q -> q.service().equals(service))
                .map(q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", q.id());
                    m.put("name", q.name());
                    m.put("sql", q.sql());
                    return m;
                })
                .toList();
    }

    // ---- helpers ----

    private EngineSession.Answer withProject(Function<ProjectSpec, EngineSession.Answer> call) {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        try {
            return call.apply(current.get().project());
        } catch (DataException e) {
            return new EngineSession.Answer(422, ApiJson.error(e.getMessage()));
        }
    }

    private static Optional<TableFile.Format> format(Object o) {
        if (!(o instanceof String s)) {
            return Optional.empty();
        }
        try {
            return Optional.of(TableFile.Format.valueOf(s.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> text(Object o) {
        return o instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }

    private static EngineSession.Answer bad(String message) {
        return new EngineSession.Answer(400, ApiJson.error(message));
    }

    private String newId() {
        byte[] b = new byte[6];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    /** Fails once more than {@code max} characters were written, so an export without a file can't eat the memory. */
    private static final class LimitedWriter extends Writer {
        private final Writer out;
        private final long max;
        private long written;

        LimitedWriter(Writer out, long max) {
            this.out = out;
            this.max = max;
        }

        @Override
        public void write(char[] buf, int off, int len) throws IOException {
            written += len;
            if (written > max) {
                throw new DataException("the export is bigger than " + max / 1024 / 1024 + " MB: export to a file");
            }
            out.write(buf, off, len);
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.close();
        }
    }
}
