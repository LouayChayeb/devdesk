package io.github.louch.devdesk.core.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FolderScanTest {

    /** An in-memory folder: relative path → content. */
    private record FakeFolder(String name, Map<String, String> contents) implements FolderScan.Folder {
        @Override
        public List<String> subfolders() {
            return contents.keySet().stream()
                    .filter(f -> f.contains("/"))
                    .map(f -> f.substring(0, f.indexOf('/')))
                    .distinct()
                    .toList();
        }

        @Override
        public List<String> files() {
            return contents.keySet().stream().filter(f -> !f.contains("/")).toList();
        }

        @Override
        public Optional<String> read(String path) {
            return Optional.ofNullable(contents.get(path));
        }
    }

    @Test
    void appsInSubfoldersAndTheDatabaseFromComposeBecomeOneDraft() {
        var folder = new FakeFolder(
                "Shop",
                Map.of(
                        "docker-compose.yml", "services:\n  db:\n    image: postgres:18\n    ports: [\"5432:5432\"]\n",
                        "web/package.json",
                                "{\"scripts\": {\"dev\": \"vite\"}, \"devDependencies\": {\"vite\": \"^7\"}}",
                        "web/pnpm-lock.yaml", "",
                        "api/pom.xml", "<project><artifactId>spring-boot-starter-actuator</artifactId></project>",
                        "api/mvnw", "",
                        "api/src/main/resources/application.properties", "server.port=8081\n",
                        "node_modules/x/package.json", "{\"scripts\": {\"start\": \"node x\"}}",
                        ".env", "DB_PASSWORD=x\nPORT=1\n"));

        var result = FolderScan.scan(folder, "/home/me/shop", List.of());
        var services = result.draft().services();

        assertThat(result.draft().name()).isEqualTo("shop");
        assertThat(services).extracting(SetupDraft.Service::name).containsExactly("db", "api", "web");
        var api = services.get(1);
        assertThat(api.command()).isEqualTo("./mvnw spring-boot:run");
        assertThat(api.directory()).isEqualTo("api");
        assertThat(api.ports()).containsExactly("8081");
        assertThat(api.health()).isEqualTo(SetupDraft.Health.http("http://127.0.0.1:8081/actuator/health"));
        // nothing said who needs the database: the guess is "everyone waits for it"
        assertThat(api.dependsOn()).containsExactly(Map.entry("db", "healthy"));
        var web = services.get(2);
        assertThat(web.command()).isEqualTo("pnpm run dev");
        assertThat(web.ports()).containsExactly("5173");
        assertThat(web.notes()).anySatisfy(n -> assertThat(n).contains("run \"pnpm install\" in ./web"));
        assertThat(result.draft().envFiles()).containsExactly(".env");
        assertThat(result.found()).contains("docker-compose.yml: 1 service", ".env: 2 variables");
    }

    @Test
    void aContainerStartedByHandIsSuggestedWithItsSettingsAndComposeOnesOnlyWarn() {
        var folder = new FakeFolder("api", Map.of("package.json", "{\"scripts\": {\"start\": \"node server.js\"}}"));
        var byHand = new FolderScan.RunningContainer(
                "my-pg",
                "postgres:16",
                List.of("5432:5432"),
                Map.of("POSTGRES_PASSWORD", "pw"),
                List.of("pgdata:/var/lib/postgresql/data"),
                "",
                false);
        var fromCompose = new FolderScan.RunningContainer(
                "api-db-1", "postgres:16", List.of(), Map.of(), List.of(), "/work/api", false);
        var ours = new FolderScan.RunningContainer(
                "devdesk-x-db", "postgres:16", List.of(), Map.of(), List.of(), "", true);

        var result = FolderScan.scan(folder, "/work/api", List.of(byHand, fromCompose, ours));

        assertThat(result.suggestions()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("postgres");
            assertThat(s.environment()).containsEntry("POSTGRES_PASSWORD", "pw");
            assertThat(s.volumes()).containsExactly("pgdata:/var/lib/postgresql/data");
            assertThat(s.origin()).isEqualTo("running now as my-pg");
        });
        assertThat(result.draft().notes()).anySatisfy(n -> assertThat(n).startsWith("docker compose is running"));
        assertThat(result.draft().services()).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("api");
            assertThat(s.command()).isEqualTo("npm run start");
        });
    }

    @Test
    void plainPythonScriptsThatKeepRunningBecomeCommands() {
        var folder = new FakeFolder(
                "tools",
                Map.of(
                        "api.py",
                                "PORT = int(os.environ[\"PORT\"])\nHTTPServer((\"127.0.0.1\", 8000), H).serve_forever()\n",
                        "worker.py", "while True:\n    work()\n",
                        "helpers.py", "def add(a, b):\n    return a + b\n"));

        var services = FolderScan.scan(folder, "/t", List.of()).draft().services();

        assertThat(services)
                .extracting(SetupDraft.Service::command)
                .containsExactly("python3 api.py", "python3 worker.py");
        var api = services.getFirst();
        assertThat(api.ports()).containsExactly("8000");
        assertThat(api.environment()).containsExactly(Map.entry("PORT", "")); // it would crash without it
        assertThat(api.notes()).singleElement().asString().contains("reads PORT");
    }

    @Test
    void anEmptyFolderSaysWhatToDo() {
        var result = FolderScan.scan(new FakeFolder("empty", Map.of()), "/e", List.of());

        assertThat(result.draft().services()).isEmpty();
        assertThat(result.draft().notes()).singleElement().asString().contains("Add database");
    }

    @Test
    void anUnreadableComposeFileSaysWhyInsteadOfZeroServices() {
        // A real project had a key twice in one service; Compose itself refuses that file too.
        var folder = new FakeFolder(
                "Shop", Map.of("docker-compose.yaml", "services:\n  app:\n    image: x\n    image: y\n"));

        var result = FolderScan.scan(folder, "/home/me/shop", List.of());

        assertThat(result.found())
                .anyMatch(f -> f.startsWith("docker-compose.yaml: could not be read") && !f.contains("0 services"));
        assertThat(result.draft().notes()).anyMatch(n -> n.startsWith("docker-compose.yaml could not be read"));
    }

    @Test
    void builtAppsGetACommandFromNodeMainFlaskOrTheDockerfile() {
        // Like dockersamples/example-voting-app: a Flask app, a Node app without a dev script, and a .NET worker.
        var folder = new FakeFolder(
                "voting",
                Map.of(
                        "docker-compose.yml",
                                "services:\n  vote:\n    build: ./vote\n  result:\n    build: ./result\n"
                                        + "  worker:\n    build: ./worker\n",
                        "vote/app.py", "from flask import Flask\napp = Flask(__name__)\n",
                        "vote/requirements.txt", "Flask\nRedis\n",
                        "result/package.json", "{\"main\": \"server.js\", \"dependencies\": {\"express\": \"4\"}}",
                        "result/server.js", "server.listen(4000)\n",
                        "worker/Dockerfile",
                                "FROM mcr.microsoft.com/dotnet/sdk\nENTRYPOINT [\"dotnet\", \"Worker.dll\"]\n"));

        var services =
                FolderScan.scan(folder, "/home/me/voting", List.of()).draft().services();
        Map<String, String> commands = new java.util.LinkedHashMap<>();
        services.forEach(s -> commands.put(s.name(), s.command()));

        assertThat(commands)
                .containsEntry("vote", "flask --app app run --port 5000")
                .containsEntry("result", "node server.js")
                .containsEntry("worker", "dotnet Worker.dll");
    }

    @Test
    void aTestFileIsNotAServiceAndFlaskenvNamesTheApp() {
        // Like miguelgrinberg/microblog: an app factory named in .flaskenv, and a tests.py with a __main__ block.
        var folder = new FakeFolder(
                "microblog",
                Map.of(
                        ".flaskenv", "FLASK_APP=microblog.py\nFLASK_DEBUG=1\n",
                        "requirements.txt", "flask==3.0\n",
                        "microblog.py", "from app import create_app\napp = create_app()\n",
                        "tests.py", "import unittest\nif __name__ == '__main__':\n    unittest.main()\n"));

        var services =
                FolderScan.scan(folder, "/home/me/microblog", List.of()).draft().services();

        assertThat(services)
                .extracting(SetupDraft.Service::command)
                .containsExactly("flask --app microblog run --port 5000");
    }

    @Test
    void aGoModuleRunsWithGoRun() {
        // Like gothinkster/golang-gin-realworld-example-app: package main lives in hello.go.
        var folder = new FakeFolder(
                "realworld",
                Map.of(
                        "go.mod", "module example.com/realworld\n",
                        "doc.go", "package main\n",
                        "hello.go",
                                "package main\nfunc main() { port := os.Getenv(\"PORT\"); if port == \"\" { port = \"8080\" } }\n"));

        var services =
                FolderScan.scan(folder, "/home/me/realworld", List.of()).draft().services();

        assertThat(services).singleElement().satisfies(s -> {
            assertThat(s.command()).isEqualTo("go run .");
            assertThat(s.ports()).containsExactly("8080");
            assertThat(s.notes()).anyMatch(n -> n.contains("reads PORT"));
        });
    }
}
