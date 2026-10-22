package io.github.louch.devdesk.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseBackups;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Backups of Postgres and MySQL/MariaDB with the dump tools that ship inside their own images, run with
 * {@code docker exec}. Nothing has to be installed on the host, and the tool always matches the server's version.
 */
public final class DockerDatabaseBackups implements DatabaseBackups {

    /** A dump or restore of a local dev database that takes longer than this is stuck. */
    private static final long TIMEOUT_MINUTES = 30;

    private final DockerClient docker;

    DockerDatabaseBackups(DockerClient docker) {
        this.docker = docker;
    }

    @Override
    public void save(String project, DatabaseTarget t, Path file) {
        String container = DockerContainerRuntime.containerName(project, t.service());
        List<String> command = switch (t.kind()) {
            // --clean --if-exists: the file drops each table before creating it, so restoring replaces what's there.
            case POSTGRES ->
                List.of("pg_dump", "-U", t.user(), "-d", t.database(), "--clean", "--if-exists", "--no-owner");
            // --single-transaction: a consistent snapshot without locking the tables while the app runs.
            // MariaDB 11 images only have "mariadb-dump"; MySQL images only "mysqldump".
            case MYSQL ->
                List.of(
                        "sh",
                        "-c",
                        // MySQL with GTIDs on (the default since 8.x images) writes a "SET @@GLOBAL.GTID_PURGED"
                        // line that a restore into the same server refuses; it only matters for replication.
                        "if command -v mariadb-dump >/dev/null; then exec mariadb-dump \"$@\"; else exec mysqldump"
                                + " --set-gtid-purged=OFF \"$@\"; fi",
                        "sh",
                        "-u" + t.user(),
                        "--single-transaction",
                        "--routines",
                        "--triggers",
                        "--databases",
                        t.database());
            // --archive: every database in one stream on stdout. The login comes from the environment (below), so
            // the password never shows in the container's process list.
            case MONGO ->
                List.of(
                        "sh",
                        "-c",
                        "exec mongodump --archive --quiet ${MONGO_USER:+--username \"$MONGO_USER\" --password"
                                + " \"$MONGO_PW\" --authenticationDatabase admin}");
            case SQLITE, REDIS, OBJECTS, SEARCH ->
                throw new IllegalArgumentException("not a database DevDesk backs up: " + t.kind());
        };
        if (t.kind() == DatabaseTarget.Kind.MYSQL && t.database().isEmpty()) {
            throw new DataException(
                    "no database is named in devdesk.yaml (MYSQL_DATABASE), so there is nothing to save");
        }
        try (OutputStream out = Files.newOutputStream(file)) {
            Result r = exec(container, command, password(t), out);
            if (r.code != 0) {
                throw new DataException(tool(t, true) + " failed (exit code " + r.code + "): " + r.errors.strip());
            }
        } catch (IOException | UncheckedIOException e) {
            deleteQuietly(file);
            throw new DataException("couldn't write the backup to " + file + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            deleteQuietly(file);
            throw e;
        }
    }

    /**
     * Drops every schema of the database (pg_* and information_schema belong to Postgres itself) and makes an empty
     * public schema owned like a new database's. pg_dump doesn't create "public", so the restore needs it there.
     */
    static final String EMPTY_POSTGRES = """
            DO $$ DECLARE s name; BEGIN
              FOR s IN SELECT nspname FROM pg_namespace
                       WHERE nspname NOT LIKE 'pg\\_%' AND nspname <> 'information_schema' LOOP
                EXECUTE format('DROP SCHEMA %I CASCADE', s);
              END LOOP;
              IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pg_database_owner') THEN
                CREATE SCHEMA public AUTHORIZATION pg_database_owner;
              ELSE
                CREATE SCHEMA public;
              END IF;
              GRANT USAGE ON SCHEMA public TO PUBLIC;
            END $$;""";

    /** mongosh: drop every database except MongoDB's own (admin, config, local) before the archive comes back. */
    static final String DROP_MONGO_DATABASES = "mongosh --quiet ${MONGO_USER:+--username \"$MONGO_USER\" --password"
            + " \"$MONGO_PW\" --authenticationDatabase admin} --eval 'db.adminCommand({listDatabases: 1}).databases"
            + ".map(d => d.name).filter(n => ![\"admin\", \"config\", \"local\"].includes(n))"
            + ".forEach(n => db.getSiblingDB(n).dropDatabase())' >/dev/null || exit 1; ";

    @Override
    public void restore(String project, DatabaseTarget t, Path file) {
        restore(project, t, file, false);
    }

    @Override
    public void restoreExactly(String project, DatabaseTarget t, Path file) {
        restore(project, t, file, true);
    }

    /** @param exact empty the database first, so what was created after the save goes too */
    private void restore(String project, DatabaseTarget t, Path file, boolean exact) {
        String container = DockerContainerRuntime.containerName(project, t.service());
        // The file goes into the container's /tmp first (like docker cp), so the tool can read it there.
        try {
            docker.copyArchiveToContainerCmd(container)
                    .withHostResource(file.toString())
                    .withRemotePath("/tmp")
                    .exec();
        } catch (DockerException e) {
            throw new DataException("couldn't copy the backup into " + container + ": " + e.getMessage(), e);
        }
        String copied = "/tmp/" + file.getFileName();
        List<String> command = switch (t.kind()) {
            // --single-transaction + ON_ERROR_STOP: one failing statement undoes the whole restore.
            case POSTGRES -> {
                List<String> psql = new ArrayList<>(List.of(
                        "psql",
                        "-q",
                        "-U",
                        t.user(),
                        "-d",
                        t.database(),
                        "-v",
                        "ON_ERROR_STOP=1",
                        "--single-transaction"));
                if (exact) {
                    // -c then -f run in order inside the one transaction: if the restore fails, nothing was dropped.
                    psql.addAll(List.of("-c", EMPTY_POSTGRES));
                }
                psql.addAll(List.of("-f", copied));
                yield psql;
            }
            case MYSQL ->
                List.of(
                        "sh",
                        "-c",
                        "if command -v mariadb >/dev/null; then c=mariadb; else c=mysql; fi; "
                                // The dump creates the database again if it's missing (it was saved with
                                // --databases), so dropping it first leaves exactly what was saved.
                                + "{ if [ -n \"$3\" ]; then printf 'DROP DATABASE IF EXISTS %s;\\n' \"$3\"; fi; cat \"$2\"; }"
                                + " | \"$c\" -u\"$1\"",
                        "sh",
                        t.user(),
                        copied,
                        exact && !t.database().isEmpty() ? "`" + t.database().replace("`", "``") + "`" : "");
            // --drop: each collection in the archive replaces the one there, like the SQL restores do.
            case MONGO ->
                List.of(
                        "sh",
                        "-c",
                        (exact ? DROP_MONGO_DATABASES : "")
                                + "exec mongorestore --archive=\"$1\" --drop --quiet ${MONGO_USER:+--username \"$MONGO_USER\""
                                + " --password \"$MONGO_PW\" --authenticationDatabase admin}",
                        "sh",
                        copied);
            case SQLITE, REDIS, OBJECTS, SEARCH ->
                throw new IllegalArgumentException("not a database DevDesk backs up: " + t.kind());
        };
        try {
            Result r = exec(container, command, password(t), OutputStream.nullOutputStream());
            if (r.code != 0) {
                throw new DataException(tool(t, false) + " failed (exit code " + r.code + "): " + r.errors.strip()
                        + (t.kind() == DatabaseTarget.Kind.POSTGRES
                                ? " Nothing was changed: the restore runs as one transaction."
                                : ""));
            }
        } finally {
            exec(container, List.of("rm", "-f", copied), List.of(), OutputStream.nullOutputStream());
        }
    }

    /** The password goes in an environment variable of the exec, never on a command line (ps would show it). */
    private static List<String> password(DatabaseTarget t) {
        return switch (t.kind()) {
            case POSTGRES -> List.of("PGPASSWORD=" + t.password());
            case MYSQL -> List.of("MYSQL_PWD=" + t.password());
            case MONGO ->
                t.user().isEmpty() ? List.of() : List.of("MONGO_USER=" + t.user(), "MONGO_PW=" + t.password());
            default -> List.of();
        };
    }

    private record Result(long code, String errors) {}

    /** For messages: which tool ran. */
    private static String tool(DatabaseTarget t, boolean save) {
        return switch (t.kind()) {
            case POSTGRES -> save ? "pg_dump" : "psql";
            case MONGO -> save ? "mongodump" : "mongorestore";
            default -> save ? "mysqldump" : "mysql";
        };
    }

    /** Runs a command in the container: stdout to {@code out}, stderr kept for the error message. */
    private Result exec(String container, List<String> command, List<String> env, OutputStream out) {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        try {
            String id = docker.execCreateCmd(container)
                    .withCmd(command.toArray(String[]::new))
                    .withEnv(env)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec()
                    .getId();
            var callback = docker.execStartCmd(id).exec(new ResultCallback.Adapter<Frame>() {
                @Override
                public void onNext(Frame frame) {
                    try {
                        (frame.getStreamType() == StreamType.STDERR ? errors : out).write(frame.getPayload());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });
            if (!callback.awaitCompletion(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                throw new DataException("the backup tool didn't finish within " + TIMEOUT_MINUTES + " minutes");
            }
            Long code = docker.inspectExecCmd(id).exec().getExitCodeLong();
            return new Result(code == null ? -1 : code, errors.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataException("interrupted while the backup tool was running", e);
        } catch (DockerException e) {
            throw new DataException(
                    "Docker couldn't run the backup tool in " + container + " (is the service running?): "
                            + e.getMessage(),
                    e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // nothing more to do
        }
    }
}
