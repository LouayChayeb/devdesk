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
    void anAlreadySetUpProjectHasNothingToDo() throws Exception {
        var plan = FreshSetup.of(
                ExistingSetup.read("version: 1\nservices:\n  api: { command: node server.js }\n", "x"),
                f -> Optional.empty(),
                f -> false);

        assertThat(plan.steps()).isEmpty();
        assertThat(plan.notes()).singleElement().asString().startsWith("Nothing to prepare");
    }
}
