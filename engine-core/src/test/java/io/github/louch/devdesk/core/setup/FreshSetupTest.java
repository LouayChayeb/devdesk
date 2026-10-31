package io.github.louch.devdesk.core.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FreshSetupTest {

    private static final String YAML = """
            version: 1
            env_files: [.env]
            services:
              db: { container: { image: postgres:18, ports: ["5432:5432"], environment: { POSTGRES_PASSWORD: "${DB_PASSWORD}" } } }
              cache: { container: { image: postgres:18 } }
              migrate: { command: python3 migrate.py, depends_on: { db: { condition: healthy } } }
              api:
                command: npm run dev
                directory: api
                depends_on: { migrate: { condition: completed_successfully } }
              web: { command: pnpm run dev, directory: web }
              backend: { command: ./mvnw spring-boot:run, directory: backend }
            """;

    private static FreshSetup.Plan plan(Map<String, String> files) throws Exception {
        return FreshSetup.of(
                ExistingSetup.read(YAML, "shop"), f -> Optional.ofNullable(files.get(f)), files::containsKey);
    }

    @Test
    void aFreshCloneGetsSettingsPackagesImagesAndJobsInThatOrder() throws Exception {
        var plan = plan(Map.of(
                ".env.example", "# database\nDB_PASSWORD=\nLOG_LEVEL=info\nSTRIPE_KEY=changeme\n",
                "requirements.txt", "psycopg\n",
                "api/package.json", "{}",
                "api/package-lock.json", "{}",
                "web/package.json", "{}",
                "web/pnpm-lock.yaml", "",
                "web/node_modules/.modules.yaml", "",
                "backend/pom.xml", "<project/>",
                "backend/mvnw", ""));

        assertThat(plan.steps())
                .extracting(FreshSetup.Step::id)
                .containsExactly(
                        "env:.env", "install:", "install:api", "install:backend", "pull:postgres:18", "job:migrate");
        var env = (FreshSetup.Step.CreateEnv) plan.steps().getFirst();
        assertThat(env.title()).isEqualTo("Create .env from .env.example");
        // only the empty and placeholder values are asked; LOG_LEVEL=info is a real default
        assertThat(env.questions()).extracting(FreshSetup.Question::key).containsExactly("DB_PASSWORD", "STRIPE_KEY");
        assertThat(env.questions()).allMatch(FreshSetup.Question::secret);
        assertThat(plan.steps())
                .filteredOn(s -> s.id().equals("install:api"))
                .singleElement()
                .isEqualTo(new FreshSetup.Step.Run(
                        "install:api", "Install api's packages (npm ci in ./api)", "api", "npm ci"));
        assertThat(((FreshSetup.Step.Run) plan.steps().get(1)).command())
                .isEqualTo("python3 -m venv .venv && .venv/bin/pip install -r requirements.txt");
        assertThat(((FreshSetup.Step.Run) plan.steps().get(3)).command())
                .isEqualTo("./mvnw -B -q dependency:go-offline");
        assertThat(plan.notes())
                .singleElement()
                .asString()
                .startsWith("migrate starts with \"python3\", not .venv/bin/python");
    }

    @Test
    void workspaceMembersInstallOnceFromTheRootWithItsPinnedManager() throws Exception {
        // Found on excalidraw: "npm install" inside excalidraw-app failed (exit 127); its yarn workspace installs
        // from the root, with the yarn version the root package.json pins.
        var draft = ExistingSetup.read("""
                version: 1
                services:
                  app: { command: corepack yarn start, directory: excalidraw-app }
                  docs: { command: corepack yarn start, directory: dev-docs }
                """, "excalidraw");
        Map<String, String> files = Map.of(
                "package.json",
                        "{\"packageManager\": \"yarn@1.22.22\", \"workspaces\": [\"excalidraw-app\", \"dev-docs\"]}",
                "yarn.lock", "",
                "excalidraw-app/package.json", "{}",
                "dev-docs/package.json", "{}");

        var plan = FreshSetup.of(draft, f -> Optional.ofNullable(files.get(f)), files::containsKey);

        assertThat(plan.steps())
                .singleElement()
                .isEqualTo(new FreshSetup.Step.Run(
                        "install:",
                        "Install the workspace's packages, app's included (corepack yarn install in the project folder)",
                        "",
                        "corepack yarn install"));
    }

    @Test
    void withoutAnExampleTheVariablesDevdeskYamlUsesAreAsked() throws Exception {
        var env = (FreshSetup.Step.CreateEnv) plan(Map.of()).steps().getFirst();

        assertThat(env.title()).isEqualTo("Create .env");
        assertThat(env.questions()).extracting(FreshSetup.Question::key).containsExactly("DB_PASSWORD");
    }

    @Test
    void theNewEnvFileKeepsTheExamplesLinesAndQuotesWhenNeeded() {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("DB_PASSWORD", "s3cret pass");
        answers.put("EXTRA", "1");

        String content = FreshSetup.envContent("# database\nDB_PASSWORD=\nLOG_LEVEL=info\n", answers);

        assertThat(content).isEqualTo("# database\nDB_PASSWORD=\"s3cret pass\"\nLOG_LEVEL=info\nEXTRA=1\n");
    }

    @Test
    void builtImagesAreNotDownloadedAndGoAndDotnetGetTheirInstalls() throws Exception {
        var draft = ExistingSetup.read("""
                version: 1
                services:
                  web: { container: { image: shop-web, build: ./web } }
                  db: { container: { image: postgres:18 } }
                  api: { command: go run ., directory: api }
                  worker: { command: dotnet run, directory: worker }
                """, "shop");

        var plan = FreshSetup.of(
                draft, f -> f.equals("api/go.mod") ? Optional.of("module shop\n") : Optional.empty(), f -> false);

        // shop-web isn't on any registry: DevDesk builds it when it starts.
        assertThat(plan.steps())
                .extracting(FreshSetup.Step::id)
                .containsExactly("install:api", "install:worker", "pull:postgres:18");
        assertThat(plan.steps())
                .filteredOn(s -> s instanceof FreshSetup.Step.Run)
                .extracting(s -> ((FreshSetup.Step.Run) s).command())
                .containsExactly("go mod download", "dotnet restore", "docker pull postgres:18");
    }

    @Test
    void theProjectsOwnPlaceholdersAreAskedForAndRealValuesArent() throws Exception {
        var draft = ExistingSetup.read("version: 1\nenv_files: [.env]\nservices:\n  web: { command: rails s }\n", "x");
        String example = String.join(
                "\n",
                "SECRET_KEY_BASE=replace_with_lengthy_secure_hex", // Chatwoot
                "ENCRYPTION_KEY=replace_me_with_a_random_string", // Twenty
                "API_TOKEN=changethis",
                "ADMIN_PASSWORD=<your password>",
                "REDIS_URL=redis://redis:6379",
                "CHANGELOG_URL=https://example.com/changelog",
                "REPLACEMENT_POLICY=never",
                "");

        var plan = FreshSetup.of(
                draft, f -> f.equals(".env.example") ? Optional.of(example) : Optional.empty(), f -> false);

        assertThat(((FreshSetup.Step.CreateEnv) plan.steps().getFirst()).questions())
                .extracting(FreshSetup.Question::key)
                .containsExactly("SECRET_KEY_BASE", "ENCRYPTION_KEY", "API_TOKEN", "ADMIN_PASSWORD");
    }

    @Test
    void anImageNameIsResolvedBeforeItsDownload() throws Exception {
        // Twenty: twentycrm/twenty:${TAG:-latest} with TAG=v2.52.0 in .env got "latest" when the shell resolved it.
        var draft = ExistingSetup.read(
                "version: 1\nenv_files: [.env]\nservices:\n  server: { container: { image: \"twentycrm/twenty:${TAG:-latest}\" } }\n"
                        + "  worker: { container: { image: \"twentycrm/twenty:${TAG:-latest}\" } }\n  db: { container: { image: \"postgres:${PG:-16}\" } }\n",
                "twenty");

        var plan = FreshSetup.of(
                draft, f -> f.equals(".env") ? Optional.of("TAG=v2.52.0\n") : Optional.empty(), f -> false);

        assertThat(plan.steps())
                .extracting(FreshSetup.Step::id)
                .containsExactly("pull:twentycrm/twenty:v2.52.0", "pull:postgres:16");
    }

    @Test
    void placeholderSecretsInAnEnvFileTheProjectShipsAreAskedFor() throws Exception {
        // Like paperless-ngx's docker-compose.env: its current image refuses PAPERLESS_SECRET_KEY=change-me.
        var draft = ExistingSetup.read(
                "version: 1\nenv_files: [docker-compose.env]\nservices:\n  web: { container: { image: paperless } }\n",
                "paperless");
        String env = "PAPERLESS_SECRET_KEY=change-me\nPAPERLESS_TIME_ZONE=changeme\nPAPERLESS_OCR_LANGUAGE=eng\n";

        var plan = FreshSetup.of(
                draft, f -> f.equals("docker-compose.env") ? Optional.of(env) : Optional.empty(), f -> false);

        assertThat(plan.steps().getFirst())
                .isEqualTo(new FreshSetup.Step.FillEnv(
                        "fill:docker-compose.env",
                        "Replace 1 placeholder value in docker-compose.env",
                        "docker-compose.env",
                        java.util.List.of(new FreshSetup.Question("PAPERLESS_SECRET_KEY", "change-me", true))));
    }

    @Test
    void anAlreadySetUpProjectHasNothingToDo() throws Exception {
        var plan = FreshSetup.of(
                ExistingSetup.read("version: 1\nservices:\n  api: { command: node server.js }\n", "x"),
                f -> Optional.empty(),
                f -> false);

        assertThat(plan.steps()).isEmpty();
        assertThat(plan.notes()).singleElement().asString().startsWith("Nothing to prepare");
    }
}
