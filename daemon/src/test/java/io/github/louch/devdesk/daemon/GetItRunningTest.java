package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Get it running", end to end through the engine's API, like the app: a link to a git repository on disk (no
 * network for the clone) holding a compose app DevDesk must build and a database, set up, started until healthy, then
 * removed. Afterwards, checked from the outside like the real-project trials: nothing of it is left.
 */
@Tag("docker")
class GetItRunningTest {

    private static final String BUILDER =
            "devdesk-test-get-" + ProcessHandle.current().pid();
    /** Unique per run: compose names its volume after the folder, and a failed run must not meet the next one. */
    private static final String NAME = "shop" + ProcessHandle.current().pid();

    private static boolean ready;

    @TempDir
    Path tmp;

    @BeforeAll
    static void prepare() throws Exception {
        ready = run("docker", "info") == 0
                // The stand-in database's image: present before the test, so it's never DevDesk's to remove.
                && (run("docker", "image", "inspect", "alpine:3") == 0 || run("docker", "pull", "-q", "alpine:3") == 0)
                // A builder of its own, removed with its cache afterwards; on the host network for WSL's DNS.
                && run(
                                "docker",
                                "buildx",
                                "create",
                                "--name",
                                BUILDER,
                                "--driver",
                                "docker-container",
                                "--driver-opt",
                                "network=host")
                        == 0;
    }

    @AfterAll
    static void removeBuilder() throws Exception {
        run("docker", "buildx", "rm", "--force", BUILDER);
        // Only if the test failed before Remove: what it made, found by its labels.
        for (String kind : List.of("container", "volume", "network", "image")) {
            for (String id : docker(kind, "ls", "-q", "--filter", "label=devdesk.project=" + NAME)
                    .split("\\s+")) {
                if (!id.isBlank()) {
                    run("docker", kind, "rm", "-f", id);
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProjectFromALinkRunsThenRemoveLeavesNothing() throws Exception {
        assumeTrue(ready, "Docker or docker buildx isn't available");
        int appPort = freePort();
        int dbPort = freePort();
        Path repo = repository(appPort, dbPort);
        Path home = tmp.resolve("home");
        Files.createDirectories(home);
        try (TestEngine engine = TestEngine.start(
                home,
                null,
                false,
                List.of(),
                // The engine's home is a throwaway one; Docker's own settings (where builders are) stay the user's.
                Map.of(
                        "BUILDX_BUILDER",
                        BUILDER,
                        "DOCKER_CONFIG",
                        Path.of(System.getProperty("user.home"), ".docker").toString()))) {
            // 1. The link: cloned into ~/DevDesk.
            assertThat(engine.call("POST /get", "{\"link\": \"file://" + repo + "\"}")
                            .statusCode())
                    .isEqualTo(200);
            Map<String, Object> got = await(engine, "GET /get", m -> !"cloning".equals(m.get("state")));
            assertThat(got.get("state")).as(String.valueOf(got)).isEqualTo("done");
            Path folder = Path.of((String) got.get("folder"));
            assertThat(folder).isEqualTo(home.resolve("DevDesk/" + NAME));

            // 2. The wizard's draft: the app is built from its Dockerfile, the database runs from its image.
            Map<String, Object> scan = json(engine.call("POST /setup/scan", "{\"path\": \"" + folder + "\"}")
                    .body());
            Map<String, Object> draft = (Map<String, Object>) scan.get("draft");
            List<Map<String, Object>> services = (List<Map<String, Object>>) draft.get("services");
            assertThat(services).extracting(s -> s.get("name")).containsExactly("db", "app");
            assertThat(services).allMatch(s -> "container".equals(s.get("kind")));
            String body = ApiJson.MAPPER.writeValueAsString(Map.of("path", folder.toString(), "draft", draft));
            assertThat(engine.call("POST /setup/write", body).statusCode()).isEqualTo(200);
            assertThat(engine.call("POST /project/trust").statusCode()).isEqualTo(200);

            // 3. Started: built, healthy, answering.
            engine.call("POST /project/up");
            await(engine, "GET /project", GetItRunningTest::allHealthy);
            try (var s = new java.net.Socket("127.0.0.1", appPort)) {
                assertThat(new String(s.getInputStream().readNBytes(2))).isEqualTo("ok");
            }
            String key = folder.getFileName() + "-" + keyHash(folder);

            // 4. Remove: the list first, then everything goes, and a fresh look finds nothing.
            Map<String, Object> plan = json(engine.call("GET /project/removal").body());
            assertThat((Boolean) plan.get("cloned")).isTrue();
            assertThat((Boolean) plan.get("losesData")).isTrue(); // the database's volume and the folder
            Map<String, Object> report = json(
                    engine.call("POST /project/remove", "{\"confirm\": true}").body());
            assertThat((List<?>) report.get("failed")).isEmpty();
            assertThat((List<?>) report.get("leftovers")).isEmpty();
            assertThat((Boolean) report.get("clean")).isTrue();

            // 5. From the outside, like the trial runner.
            assertThat(docker("ps", "-aq", "--filter", "label=devdesk.project-key=" + key))
                    .isEmpty();
            assertThat(docker("network", "ls", "-q", "--filter", "label=devdesk.project-key=" + key))
                    .isEmpty();
            assertThat(docker("volume", "ls", "-q", "--filter", "label=devdesk.project-key=" + key))
                    .isEmpty();
            assertThat(docker("images", "-q", "--filter", "label=devdesk.project-key=" + key))
                    .isEmpty();
            assertThat(docker("volume", "ls", "-q", "--filter", "name=shop_dbdata"))
                    .isEmpty();
            assertThat(docker("image", "inspect", "-f", "{{.Id}}", "alpine:3")).isNotEmpty(); // not DevDesk's
            assertThat(folder).doesNotExist();
            assertThat(repo).exists();
            try (var files = Files.walk(home.resolve("state"))) {
                assertThat(files.map(Path::toString)).noneMatch(f -> f.contains(key));
            }
            for (int port : new int[] {appPort, dbPort}) {
                try (var s = new java.net.Socket()) {
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
                    throw new AssertionError("port " + port + " still answers");
                } catch (java.io.IOException expected) {
                    // nothing listens any more
                }
            }
        }
    }

    /** A compose project in a git repository: the app is built from a Dockerfile, the "database" is an image. */
    private Path repository(int appPort, int dbPort) throws Exception {
        Path repo = tmp.resolve("origin/" + NAME);
        Files.createDirectories(repo.resolve("app"));
        Files.writeString(repo.resolve("app/Dockerfile"), """
                # syntax=docker/dockerfile:1
                FROM alpine:3
                RUN --mount=type=cache,target=/var/cache/apk echo built
                CMD ["nc", "-lk", "-p", "8000", "-e", "echo", "ok"]
                """);
        Files.writeString(repo.resolve("docker-compose.yml"), """
                services:
                  db:
                    image: alpine:3
                    command: ["nc", "-lk", "-p", "5432", "-e", "echo", "db"]
                    ports: ["%d:5432"]
                    volumes: [dbdata:/data]
                  app:
                    build: ./app
                    ports: ["%d:8000"]
                    depends_on: [db]
                volumes:
                  dbdata:
                """.formatted(dbPort, appPort));
        git(repo, "init", "-q");
        git(repo, "add", ".");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@localhost", "commit", "-q", "-m", "shop");
        return repo;
    }

    @SuppressWarnings("unchecked")
    private static boolean allHealthy(Map<String, Object> view) {
        List<Map<String, Object>> services = (List<Map<String, Object>>) view.get("services");
        return services != null
                && !services.isEmpty()
                && services.stream()
                        .allMatch(s -> "healthy".equals(((Map<String, Object>) s.get("state")).get("label")));
    }

    private static Map<String, Object> await(
            TestEngine engine, String route, java.util.function.Predicate<Map<String, Object>> done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(300);
        Map<String, Object> last = Map.of();
        while (System.nanoTime() < deadline) {
            last = json(engine.call(route).body());
            if (done.test(last)) {
                return last;
            }
            if (String.valueOf(last).contains("label=failed")) {
                throw new AssertionError(route + " failed: " + last);
            }
            Thread.sleep(300);
        }
        throw new AssertionError(route + " never got there: " + last);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String body) throws Exception {
        return ApiJson.MAPPER.readValue(body, Map.class);
    }

    /** The project key's hash part, as ProjectSpec computes it from the folder. */
    private static String keyHash(Path folder) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(folder.toAbsolutePath()
                        .normalize()
                        .toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(hash, 0, 4);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        assertThat(new ProcessBuilder(cmd).inheritIO().start().waitFor()).isZero();
    }

    private static String docker(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("docker"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).strip();
        p.waitFor();
        return out.startsWith("Error") ? "" : out;
    }

    private static int run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        return p.waitFor();
    }
}
