package io.github.louch.devdesk.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.louch.devdesk.core.data.DatabaseTarget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Save, break, restore: against real Postgres and MySQL containers named the way DevDesk names them. */
@Tag("docker")
class DockerDatabaseBackupsTest {

    private final String project = "bk" + TestJvm.tag();
    private final List<String> started = new ArrayList<>();
    private DockerDatabaseBackups backups;

    @TempDir
    Path dir;

    @BeforeEach
    void connect() {
        var result = DockerConnection.connect(System.getenv());
        assumeTrue(result instanceof DockerConnection.Result.Connected, "Docker not available: " + result);
        TestJvm.reapOrphans(); // containers of test JVMs that were killed mid-test
        backups = ((DockerConnection.Result.Connected) result).runtime().backups();
    }

    @AfterEach
    void remove() {
        started.forEach(name -> sh("docker", "rm", "-f", "-v", name));
    }

    @Test
    void postgresComesBackAsItWas() throws Exception {
        String name = start("pg", "postgres:18.1", "POSTGRES_PASSWORD=dev", "POSTGRES_DB=shop");
        String[] psql = {"docker", "exec", name, "psql", "-U", "postgres", "-d", "shop", "-tAc"};
        waitUntil(() -> sh(append(psql, "select 1")).strip().equals("1"));
        sh(append(psql, "create table t (id int primary key, v text); insert into t values (1, 'a'), (2, 'b')"));
        var target = new DatabaseTarget("pg", DatabaseTarget.Kind.POSTGRES, "127.0.0.1", 0, "postgres", "dev", "shop");

        Path file = dir.resolve("pg.sql");
        backups.save(project, target, file);
        sh(append(psql, "delete from t; create table extra (x int)"));
        backups.restore(project, target, file);

        assertThat(sh(append(psql, "select string_agg(v, ',' order by id) from t"))
                        .strip())
                .isEqualTo("a,b");
        assertThat(Files.readString(file)).contains("CREATE TABLE public.t");
    }

    @Test
    void anExactPostgresRestoreAlsoRemovesWhatWasCreatedAfterTheSave() throws Exception {
        String name = start("pg", "postgres:18.1", "POSTGRES_PASSWORD=dev", "POSTGRES_DB=shop");
        String[] psql = {"docker", "exec", name, "psql", "-U", "postgres", "-d", "shop", "-tAc"};
        waitUntil(() -> sh(append(psql, "select 1")).strip().equals("1"));
        sh(append(
                psql,
                "create schema billing; create table billing.invoices (id int);"
                        + " create table t (id int primary key, v text); insert into t values (1, 'a')"));
        var target = new DatabaseTarget("pg", DatabaseTarget.Kind.POSTGRES, "127.0.0.1", 0, "postgres", "dev", "shop");

        Path file = dir.resolve("pg.sql");
        backups.save(project, target, file);
        sh(append(psql, "drop table t; create table junk (x int); create schema later; create table later.y (z int)"));
        backups.restoreExactly(project, target, file);

        assertThat(sh(append(
                                psql,
                                "select string_agg(table_schema || '.' || table_name, ',' order by 1) from"
                                        + " information_schema.tables where table_schema not in ('pg_catalog',"
                                        + " 'information_schema')"))
                        .strip())
                .isEqualTo("billing.invoices,public.t");
        assertThat(sh(append(psql, "select v from t")).strip()).isEqualTo("a");
    }

    @Test
    void aFailedExactPostgresRestoreChangesNothing() throws Exception {
        String name = start("pg", "postgres:18.1", "POSTGRES_PASSWORD=dev", "POSTGRES_DB=shop");
        String[] psql = {"docker", "exec", name, "psql", "-U", "postgres", "-d", "shop", "-tAc"};
        waitUntil(() -> sh(append(psql, "select 1")).strip().equals("1"));
        sh(append(psql, "create table keep (id int); insert into keep values (7)"));
        var target = new DatabaseTarget("pg", DatabaseTarget.Kind.POSTGRES, "127.0.0.1", 0, "postgres", "dev", "shop");
        Path broken = dir.resolve("broken.sql");
        Files.writeString(broken, "create table ok (id int);\nthis is not sql;\n");

        assertThatThrownBy(() -> backups.restoreExactly(project, target, broken))
                .hasMessageContaining("Nothing was changed");
        assertThat(sh(append(psql, "select id from keep")).strip()).isEqualTo("7");
    }

    @Test
    void anExactMysqlRestoreAlsoRemovesNewTables() throws Exception {
        String name = start("my", "mysql:9.5", "MYSQL_ROOT_PASSWORD=dev", "MYSQL_DATABASE=shop");
        String[] mysql = {"docker", "exec", "-e", "MYSQL_PWD=dev", name, "mysql", "-uroot", "-h127.0.0.1", "shop", "-Ne"
        };
        waitUntil(() -> sh(append(mysql, "select 1")).strip().equals("1"));
        sh(append(mysql, "create table t (id int primary key, v text); insert into t values (1, 'a')"));
        var target = new DatabaseTarget("my", DatabaseTarget.Kind.MYSQL, "127.0.0.1", 0, "root", "dev", "shop");

        Path file = dir.resolve("my.sql");
        backups.save(project, target, file);
        sh(append(mysql, "create table junk (x int); delete from t"));
        backups.restoreExactly(project, target, file);

        assertThat(sh(append(
                                mysql,
                                "select group_concat(table_name order by table_name) from"
                                        + " information_schema.tables where table_schema = 'shop'"))
                        .strip())
                .isEqualTo("t");
        assertThat(sh(append(mysql, "select v from t")).strip()).isEqualTo("a");
    }

    @Test
    void anExactMongoRestoreAlsoRemovesNewCollectionsAndDatabases() throws Exception {
        String name = start("mo", "mongo:8");
        String[] mongosh = {"docker", "exec", name, "mongosh", "--quiet", "--eval"};
        waitUntil(
                () -> sh(append(mongosh, "db.runCommand({ping: 1}).ok")).strip().equals("1"));
        sh(append(mongosh, "db.getSiblingDB('shop').orders.insertOne({_id: 1, item: 'book'})"));
        var target = new DatabaseTarget("mo", DatabaseTarget.Kind.MONGO, "127.0.0.1", 0, "", "", "shop");

        Path file = dir.resolve("mo.archive");
        backups.save(project, target, file);
        sh(append(
                mongosh,
                "db.getSiblingDB('shop').junk.insertOne({x: 1}); db.getSiblingDB('later').y.insertOne({z: 1});"
                        + " db.getSiblingDB('shop').orders.deleteMany({})"));
        backups.restoreExactly(project, target, file);

        assertThat(sh(append(mongosh, "db.getSiblingDB('shop').getCollectionNames().sort().join(',')"))
                        .strip())
                .isEqualTo("orders");
        assertThat(sh(append(
                                mongosh,
                                "db.adminCommand({listDatabases: 1}).databases.map(d => d.name).includes('later')"))
                        .strip())
                .isEqualTo("false");
        assertThat(sh(append(mongosh, "db.getSiblingDB('shop').orders.countDocuments()"))
                        .strip())
                .isEqualTo("1");
    }

    @Test
    void mysqlComesBackAsItWas() throws Exception {
        String name = start("my", "mysql:9.5", "MYSQL_ROOT_PASSWORD=dev", "MYSQL_DATABASE=shop");
        String[] mysql = {"docker", "exec", "-e", "MYSQL_PWD=dev", name, "mysql", "-uroot", "-h127.0.0.1", "shop", "-Ne"
        };
        waitUntil(() -> sh(append(mysql, "select 1")).strip().equals("1"));
        sh(append(mysql, "create table t (id int primary key, v text); insert into t values (1, 'a'), (2, 'b')"));
        var target = new DatabaseTarget("my", DatabaseTarget.Kind.MYSQL, "127.0.0.1", 0, "root", "dev", "shop");

        Path file = dir.resolve("my.sql");
        backups.save(project, target, file);
        sh(append(mysql, "delete from t"));
        backups.restore(project, target, file);

        assertThat(sh(append(mysql, "select group_concat(v order by id) from t"))
                        .strip())
                .isEqualTo("a,b");
    }

    @Test
    void aStoppedDatabaseIsExplained() {
        var target = new DatabaseTarget("nope", DatabaseTarget.Kind.POSTGRES, "127.0.0.1", 0, "postgres", "", "x");
        assertThatThrownBy(() -> backups.save(project, target, dir.resolve("x.sql")))
                .hasMessageContaining("is the service running?");
        assertThat(dir.resolve("x.sql")).doesNotExist();
    }

    private String start(String service, String image, String... env) {
        String name = DockerContainerRuntime.containerName(project, service);
        List<String> cmd = new ArrayList<>(
                List.of("docker", "run", "-d", "--rm", "--name", name, "--label", TestJvm.LABEL + "=" + TestJvm.ID));
        for (String e : env) {
            cmd.add("-e");
            cmd.add(e);
        }
        cmd.add(image);
        sh(cmd.toArray(String[]::new));
        started.add(name);
        return name;
    }

    private static String[] append(String[] base, String last) {
        String[] out = java.util.Arrays.copyOf(base, base.length + 1);
        out[base.length] = last;
        return out;
    }

    private static void waitUntil(java.util.function.BooleanSupplier ready) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            if (ready.getAsBoolean()) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("the database never came up");
    }

    private static String sh(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
