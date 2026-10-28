package io.github.louch.devdesk.core.setup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The framework's first command, which projects only write in their README, becomes a one-time card. */
class FirstRunTest {

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

    private static SetupDraft.Service named(SetupDraft draft, String name) {
        return draft.services().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /** Mastodon's shape: web and sidekiq built from the same Rails code, a database, nothing that creates tables. */
    private static final String MASTODON_COMPOSE = """
            services:
              db:
                image: postgres:14-alpine
              web:
                build: .
                image: ghcr.io/mastodon/mastodon
                command: bundle exec puma -C config/puma.rb
                ports: ["127.0.0.1:3000:3000"]
                volumes: ["./public/system:/mastodon/public/system"]
                depends_on: [db]
              sidekiq:
                build: .
                image: ghcr.io/mastodon/mastodon
                command: bundle exec sidekiq
                depends_on: [db]
            """;

    @Test
    void aRailsAppGetsItsTablesMadeBeforeItStarts() {
        var folder = new FakeFolder(
                "mastodon",
                Map.of(
                        "docker-compose.yml", MASTODON_COMPOSE,
                        "Gemfile", "gem 'rails', '~> 8.0'\n",
                        "bin/rails", "#!/usr/bin/env ruby\n"));

        var draft = FolderScan.scan(folder, "/home/me/mastodon", List.of()).draft();

        var prepare = named(draft, "web-prepare");
        assertThat(prepare.isContainer()).isTrue();
        assertThat(prepare.command()).isEqualTo("bundle exec rails db:prepare");
        assertThat(prepare.image()).isEqualTo(named(draft, "web").image()); // the same code, the same image
        assertThat(prepare.extras()).containsEntry("build", ".");
        assertThat(prepare.volumes()).isEqualTo(named(draft, "web").volumes());
        assertThat(prepare.ports()).isEmpty();
        assertThat(prepare.restart()).isEqualTo("no");
        assertThat(prepare.dependsOn()).containsOnlyKeys("db");
        assertThat(prepare.notes())
                .singleElement()
                .asString()
                .contains("is a Rails app")
                .contains("web and sidekiq");
        // Both services built from that code wait for it; the database doesn't.
        assertThat(named(draft, "web").dependsOn()).containsEntry("web-prepare", "completed_successfully");
        assertThat(named(draft, "sidekiq").dependsOn()).containsEntry("web-prepare", "completed_successfully");
        assertThat(named(draft, "db").dependsOn()).isEmpty();

        // A suggestion, never silently kept: skipping it leaves no service waiting for it.
        assertThat(FirstRun.suggested(draft))
                .extracting(SetupDraft.Service::name)
                .containsExactly("web-prepare");
        var skipped = FirstRun.without(draft, "web-prepare");
        assertThat(skipped.services()).extracting(SetupDraft.Service::name).doesNotContain("web-prepare");
        assertThat(skipped.services()).allSatisfy(s -> assertThat(s.dependsOn()).doesNotContainKey("web-prepare"));

        // Set up offers it as its one-time job.
        var plan =
                FreshSetup.of(draft, f -> Optional.ofNullable(folder.contents().get(f)), f -> false);
        assertThat(plan.steps())
                .filteredOn(s -> s instanceof FreshSetup.Step.Job)
                .extracting(FreshSetup.Step::id)
                .containsExactly("job:web-prepare");
    }

    @Test
    void aProjectThatAlreadyMigratesGetsNothingMore() {
        var folder = new FakeFolder("saleor", Map.of("docker-compose.yml", """
                        services:
                          api:
                            build: .
                            command: uvicorn saleor.asgi:application
                            depends_on: { migrate: { condition: service_completed_successfully } }
                          migrate:
                            build: .
                            command: python manage.py migrate
                        """, "manage.py", "import django\n"));

        var draft = FolderScan.scan(folder, "/home/me/saleor", List.of()).draft();

        assertThat(draft.services()).extracting(SetupDraft.Service::name).containsExactly("api", "migrate");
    }

    @Test
    void anAppOnThisMachineUsesItsOwnPythonAndFolder() {
        var draft = new SetupDraft(
                "blog",
                List.of(),
                List.of(new SetupDraft.Service(
                        "api",
                        "command",
                        ".venv/bin/python manage.py runserver 8000",
                        "api",
                        "",
                        List.of("8000"),
                        List.of(),
                        Map.of(),
                        Map.of(),
                        SetupDraft.Health.tcp(8000),
                        "on-failure",
                        "api/manage.py",
                        List.of())),
                List.of());
        Map<String, String> files = Map.of(
                "api/manage.py",
                "os.environ.setdefault('DJANGO_SETTINGS_MODULE', 'x')\n"
                        + "from django.core.management import execute_from_command_line\n");

        var out = FirstRun.offer(draft, f -> Optional.ofNullable(files.get(f)));

        var prepare = named(out, "api-prepare");
        assertThat(prepare.kind()).isEqualTo("command");
        assertThat(prepare.command()).isEqualTo(".venv/bin/python manage.py migrate");
        assertThat(prepare.directory()).isEqualTo("api");
        assertThat(prepare.health()).isEqualTo(SetupDraft.Health.PROCESS);
    }

    @Test
    void eachFrameworkIsKnownByItsFiles() {
        assertThat(FirstRun.detect(
                        f -> f.equals("prisma/schema.prisma") ? Optional.of("") : Optional.empty(), "python"))
                .map(FirstRun.Known::command)
                .contains("npx prisma migrate deploy");
        assertThat(FirstRun.detect(f -> f.equals("artisan") ? Optional.of("") : Optional.empty(), "python"))
                .map(FirstRun.Known::command)
                .contains("php artisan migrate --force");
        assertThat(FirstRun.detect(f -> f.equals("alembic.ini") ? Optional.of("") : Optional.empty(), "python"))
                .map(FirstRun.Known::command)
                .contains("alembic upgrade head");
        // A Gemfile without Rails (a Sinatra app) is no Rails app.
        assertThat(FirstRun.detect(
                        f -> switch (f) {
                            case "Gemfile" -> Optional.of("gem 'sinatra'\n");
                            case "bin/rails" -> Optional.of("");
                            default -> Optional.empty();
                        },
                        "python"))
                .isEmpty();
    }

    @Test
    void aPastedCommandBecomesACardWithAFreeName() {
        var folder = new FakeFolder(
                "mastodon",
                Map.of(
                        "docker-compose.yml", MASTODON_COMPOSE,
                        "Gemfile", "gem 'rails'\n",
                        "bin/rails", ""));
        var draft = FolderScan.scan(folder, "/home/me/mastodon", List.of()).draft();

        var out = FirstRun.add(draft, "web", "bundle exec rails mastodon:setup", "");

        var pasted = named(out, "web-prepare-2");
        assertThat(FirstRun.suggested(out)).extracting(SetupDraft.Service::name).containsExactly("web-prepare");
        assertThat(pasted.command()).isEqualTo("bundle exec rails mastodon:setup");
        assertThat(named(out, "web").dependsOn())
                .containsEntry("web-prepare", "completed_successfully")
                .containsEntry("web-prepare-2", "completed_successfully");
        // The first one-time card isn't made to wait for the second.
        assertThat(named(out, "web-prepare").dependsOn()).doesNotContainKey("web-prepare-2");
    }
}
