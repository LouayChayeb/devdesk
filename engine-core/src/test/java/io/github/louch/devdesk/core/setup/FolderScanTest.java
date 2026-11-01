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
        services.forEach(s -> commands.put(s.name(), s.extras().get("native.command")));

        // Each is built from its Dockerfile as a container, once: no "vote-2" running the same app natively.
        assertThat(services).extracting(SetupDraft.Service::name).containsExactly("vote", "result", "worker");
        assertThat(services).allMatch(SetupDraft.Service::isContainer);
        assertThat(commands)
                .containsEntry("vote", ".venv/bin/flask --app app run --port 5000")
                .containsEntry("result", "node server.js")
                .containsEntry("worker", "dotnet Worker.dll");
    }

    @Test
    void aProjectWithOnlyADockerfileIsBuiltAndRunFromIt() {
        var folder = new FakeFolder(
                "hello-api",
                Map.of(
                        "Dockerfile",
                                "FROM node:22\nCOPY . .\nRUN npm ci\nEXPOSE 8080 9229/udp\nCMD [\"node\", \"index.js\"]\n",
                        "package.json", "{\"main\": \"index.js\", \"dependencies\": {\"express\": \"4\"}}",
                        "index.js", "app.listen(8080)\n"));

        var scan = FolderScan.scan(folder, "/home/me/hello-api", List.of());
        var services = scan.draft().services();

        // One card, not a container plus the same app run natively.
        assertThat(services).singleElement().satisfies(s -> {
            assertThat(s.kind()).isEqualTo("container");
            assertThat(s.extras()).containsEntry("build", ".").containsEntry("native.command", "node index.js");
            assertThat(s.ports()).containsExactly("8080:8080"); // UDP can't be published like this
            assertThat(s.origin()).isEqualTo("Dockerfile");
        });
        assertThat(scan.found()).anyMatch(f -> f.startsWith("Dockerfile: built and run as"));
    }

    @Test
    void aComposeProjectsMissingEnvFileIsMadeFromItsSampleNeverReplacedByTheDevelopmentOne() {
        // Mastodon: web reads .env.production (only .env.production.sample is in the repository), and the root has
        // a .env.development for its own developers.
        var folder = new FakeFolder(
                "mastodon",
                Map.of(
                        "docker-compose.yml",
                        "services:\n  web:\n    image: ghcr.io/mastodon/mastodon:v4.7.3\n    env_file: .env.production\n",
                        ".env.production.sample",
                        "LOCAL_DOMAIN=example.com\nSECRET_KEY_BASE=\n",
                        ".env.development",
                        "RAILS_ENV=development\n"));

        var draft = FolderScan.scan(folder, "/home/me/mastodon", List.of()).draft();

        assertThat(draft.envFiles()).isEmpty(); // not .env.development
        var web = draft.services().getFirst();
        assertThat(web.extras()).containsEntry("env_files", ".env.production");
        assertThat(web.notes()).anyMatch(n -> n.contains("Set up makes it from .env.production.sample"));
        var plan = FreshSetup.of(draft, folder::read, f -> false);
        assertThat(plan.steps().getFirst()).isInstanceOfSatisfying(FreshSetup.Step.CreateEnv.class, e -> {
            assertThat(e.file()).isEqualTo(".env.production");
            assertThat(e.questions()).extracting(FreshSetup.Question::key).containsExactly("SECRET_KEY_BASE");
        });
    }

    @Test
    void variablesNothingSetsMakeSetUpAskForThemInDotEnv() {
        // Plausible: compose uses ${BASE_URL} and ${SECRET_KEY_BASE}, and there's no .env or example.
        var folder = new FakeFolder(
                "plausible",
                Map.of(
                        "compose.yml",
                        "services:\n  plausible:\n    image: plausible:3\n    environment:\n"
                                + "      BASE_URL: ${BASE_URL}\n      SECRET_KEY_BASE: ${SECRET_KEY_BASE}\n"));

        var draft = FolderScan.scan(folder, "/home/me/plausible", List.of()).draft();

        assertThat(draft.envFiles()).containsExactly(".env");
        var plan = FreshSetup.of(draft, folder::read, f -> false);
        assertThat(plan.steps().getFirst())
                .isInstanceOfSatisfying(
                        FreshSetup.Step.CreateEnv.class,
                        e -> assertThat(e.questions())
                                .extracting(FreshSetup.Question::key)
                                .containsExactly("BASE_URL", "SECRET_KEY_BASE"));
    }

    @Test
    void theOverrideFileIsReadOnTopLikeComposeDoes() {
        // Plausible publishes its port in compose.override.yml.
        var folder = new FakeFolder(
                "plausible",
                Map.of(
                        "compose.yml",
                        "services:\n  plausible:\n    image: plausible:3\n    command: run\n",
                        "compose.override.yml",
                        "services:\n  plausible:\n    ports:\n      - 8000:80\n    command: [run, --fast]\n"));

        var scan = FolderScan.scan(folder, "/home/me/plausible", List.of());
        var card = scan.draft().services().getFirst();

        assertThat(card.ports()).containsExactly("8000:80");
        assertThat(card.command()).isEqualTo("run --fast"); // a command is replaced, not added to
        assertThat(scan.found()).contains("compose.override.yml: read on top of compose.yml, like docker compose does");
    }

    @Test
    void mergeKeysAndEntrypointReachTheCardsAndNoNativeCopyIsAdded() {
        // Chatwoot's production compose: services get their image through <<: *base, rails starts through a script,
        // and the top folder has the app's own package.json.
        var folder = new FakeFolder(
                "chatwoot",
                Map.of(
                        "docker-compose.yml",
                        "services:\n  base: &base\n    image: chatwoot/chatwoot:v4.19.1\n    volumes:\n"
                                + "      - storage_data:/app/storage\n  rails:\n    <<: *base\n"
                                + "    entrypoint: docker/entrypoints/rails.sh\n    command: ['bundle', 'exec', 'rails', 's']\n"
                                + "    ports: ['3000:3000']\n",
                        "package.json",
                        "{\"scripts\": {\"dev\": \"pnpm run dev\"}}"));

        var services =
                FolderScan.scan(folder, "/home/me/chatwoot", List.of()).draft().services();

        assertThat(services).extracting(SetupDraft.Service::name).containsExactly("base", "rails");
        var rails = services.get(1);
        assertThat(rails.image()).isEqualTo("chatwoot/chatwoot:v4.19.1");
        assertThat(rails.volumes()).containsExactly("chatwoot_storage_data:/app/storage");
        assertThat(rails.extras()).containsEntry("entrypoint", "docker/entrypoints/rails.sh");
        assertThat(SetupYaml.write(new SetupDraft("chatwoot", List.of(), services, List.of())))
                .contains("      entrypoint: docker/entrypoints/rails.sh\n");
    }

    @Test
    void aFolderNamedLikeAComposeServiceIsThatServiceNotANewOne() {
        // Mastodon: the streaming container, and streaming/package.json next to it.
        var folder = new FakeFolder(
                "mastodon",
                Map.of(
                        "docker-compose.yml",
                        "services:\n  streaming:\n    image: ghcr.io/mastodon/mastodon-streaming:v4.7.3\n",
                        "streaming/package.json",
                        "{\"scripts\": {\"start\": \"node index.js\"}}"));

        var services =
                FolderScan.scan(folder, "/home/me/mastodon", List.of()).draft().services();

        assertThat(services).extracting(SetupDraft.Service::name).containsExactly("streaming");
    }

    @Test
    void anAppOnAPortComposeAlreadyPublishesIsNotAddedTwice() {
        // Like umami: the official image in compose, and the same app's package.json at the root.
        var folder = new FakeFolder(
                "umami",
                Map.of(
                        "docker-compose.yml",
                        "services:\n  umami:\n    image: ghcr.io/umami-software/umami\n    ports: [\"3000:3000\"]\n",
                        "package.json",
                        "{\"scripts\": {\"dev\": \"next dev -p 3000\"}}"));

        var services =
                FolderScan.scan(folder, "/home/me/umami", List.of()).draft().services();

        assertThat(services).extracting(SetupDraft.Service::name).containsExactly("umami");
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
                .containsExactly(".venv/bin/flask --app microblog run --port 5000");
    }

    @Test
    void aMonorepoWithItsComposeFileInASubfolderGetsItsDatabasesItsAppAndItsMigrateScript() {
        // Like documenso: no compose file at the top (it's docker/development/compose.yml, started by "npm run dx:up"),
        // apps two levels down (apps/remix) run by the root's "turbo run dev", tables made by "prisma:migrate-dev".
        // The scan found nothing at all.
        var folder = new FakeFolder(
                "documenso",
                Map.of(
                        "package.json",
                        """
                        {"workspaces": ["apps/*", "packages/*"],
                         "scripts": {"dev": "turbo run dev --filter=@documenso/remix",
                                     "dx:up": "docker compose -f docker/development/compose.yml up -d",
                                     "prisma:generate": "prisma generate",
                                     "prisma:migrate-dev": "npm run with:env -- npm run prisma:migrate-dev -w @documenso/prisma"},
                         "dependencies": {"knex-migrate": "1"}}
                        """,
                        "package-lock.json",
                        "{}",
                        "docker/development/compose.yml",
                        """
                        services:
                          database:
                            image: postgres:15
                            ports: ["54320:5432"]
                            volumes: ["./data:/var/lib/postgresql/data"]
                          gotenberg:
                            build: { context: ., dockerfile: Dockerfile.gotenberg }
                        """,
                        "docker/development/Dockerfile.gotenberg",
                        "FROM gotenberg/gotenberg:8\n",
                        "apps/remix/package.json",
                        "{}"));

        var result = FolderScan.scan(folder, "/home/me/documenso", List.of());
        var services = result.draft().services();

        assertThat(result.found())
                .anySatisfy(f -> assertThat(f).startsWith("docker/development/compose.yml: 2 services"));
        var db = services.stream()
                .filter(s -> s.name().equals("database"))
                .findFirst()
                .orElseThrow();
        assertThat(db.volumes()).containsExactly("docker/development/data:/var/lib/postgresql/data");
        var gotenberg = services.stream()
                .filter(s -> s.name().equals("gotenberg"))
                .findFirst()
                .orElseThrow();
        assertThat(gotenberg.extras()).containsEntry("build", "docker/development");
        assertThat(services)
                .filteredOn(s -> !s.isContainer())
                .extracting(SetupDraft.Service::command)
                .containsExactlyInAnyOrder("npm run dev", "npm run prisma:migrate-dev");
    }

    @Test
    void anImageBuiltFromTheWorkspaceRootIsTheAppNoSecondCardForItsMembers() {
        // Like excalidraw: compose builds the root Dockerfile, which builds excalidraw-app into the image. The wizard
        // also made an excalidraw-app command card: the same app twice, which then failed its own install.
        var folder = new FakeFolder(
                "excalidraw",
                Map.of(
                        "docker-compose.yml",
                                "services:\n  excalidraw:\n    build: { context: . }\n    ports: [\"3000:80\"]\n",
                        "Dockerfile", "FROM node:24\nRUN yarn build:app:docker\n",
                        "package.json", "{\"packageManager\": \"yarn@1.22.22\", \"workspaces\": [\"excalidraw-app\"]}",
                        "excalidraw-app/package.json", "{\"scripts\": {\"start\": \"yarn && vite\"}}"));

        var services = FolderScan.scan(folder, "/home/me/excalidraw", List.of())
                .draft()
                .services();

        assertThat(services).extracting(SetupDraft.Service::name).containsExactly("excalidraw");
    }

    @Test
    void aWorkspaceMemberStartsItsDevServerWithTheRootsPinnedManagerNotAServeOfABuild() {
        // excalidraw-app without the compose file: "serve" only serves build/, empty on a fresh clone, yet its port
        // answered, so the card showed healthy while serving nothing. "start" is the dev server.
        var folder = new FakeFolder(
                "excalidraw",
                Map.of(
                        "package.json",
                        "{\"packageManager\": \"yarn@1.22.22\", \"workspaces\": [\"excalidraw-app\"]}",
                        "excalidraw-app/package.json",
                        "{\"scripts\": {\"serve\": \"npx http-server build -a localhost -p 5001 -o\","
                                + " \"start\": \"yarn && vite\"}, \"dependencies\": {\"react\": \"19\"}}"));

        var app = FolderScan.scan(folder, "/home/me/excalidraw", List.of())
                .draft()
                .services()
                .getFirst();

        assertThat(app.command()).isEqualTo("corepack yarn start");
        assertThat(app.ports()).containsExactly("5173");
        assertThat(app.notes())
                .anySatisfy(n -> assertThat(n).contains("run \"corepack yarn install\" in the project folder"));
    }

    @Test
    void aPinnedPackageManagerRunsThroughCorepack() {
        // Like outline (yarn@4) and hoppscotch (pnpm@10): a plain yarn refuses them ("Corepack must currently be
        // enabled"), and pnpm may not be installed at all. Corepack comes with Node and runs the pinned version.
        var folder = new FakeFolder(
                "outline",
                Map.of(
                        "package.json",
                        "{\"packageManager\": \"yarn@4.18.0\", \"scripts\": {\"dev\": \"node server.js\"},"
                                + " \"dependencies\": {\"koa\": \"2\"}}",
                        "yarn.lock",
                        ""));

        var app = FolderScan.scan(folder, "/home/me/outline", List.of())
                .draft()
                .services()
                .getFirst();

        assertThat(app.command()).isEqualTo("corepack yarn dev");
        assertThat(app.notes()).anySatisfy(n -> assertThat(n).contains("run \"corepack yarn install\""));
    }

    @Test
    void aDjangoAppWithRequirementsRunsWithItsVenv() {
        // Like healthchecks/healthchecks: Set up installs requirements.txt into .venv, so the server and its first-run
        // card must use .venv/bin/python3. With plain python3 neither sees Django, and DevDesk asked the user to edit
        // both.
        var folder = new FakeFolder(
                "healthchecks",
                Map.of(
                        "manage.py",
                        "import os\nos.environ.setdefault('DJANGO_SETTINGS_MODULE', 'hc.settings')\n"
                                + "from django.core.management import execute_from_command_line\n",
                        "requirements.txt",
                        "Django==6.1.2\n"));

        var services = FolderScan.scan(folder, "/home/me/healthchecks", List.of())
                .draft()
                .services();

        assertThat(services)
                .extracting(SetupDraft.Service::command)
                .containsExactlyInAnyOrder(
                        ".venv/bin/python3 manage.py runserver 127.0.0.1:8000", ".venv/bin/python3 manage.py migrate");
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
