package io.github.louch.devdesk.core.setup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The first command a framework's app needs before it works ("create the tables"), which projects write in their
 * README rather than in their Docker files. Mastodon said "run rails db:setup" in its docs; DevDesk started the app on
 * an empty database and it broke. A known framework gets that command as a one-time card, which the services built
 * from the same code wait for ({@code completed_successfully}), like compose's migrate services.
 */
public final class FirstRun {

    /**
     * A framework DevDesk recognises by its files.
     *
     * @param done words in a command that show the project already does this itself (its own migrate service)
     */
    record Known(String framework, String command, Pattern done) {}

    /** The origin of a card DevDesk suggested by itself: shown as a suggestion, asked for in the CLI. */
    public static final String SUGGESTED = "suggested: the first command of a ";

    private FirstRun() {}

    /**
     * The framework of the code in a folder, read through {@code read} (paths relative to that folder). Each of these
     * commands is safe to run again: it creates what's missing and leaves the rest.
     */
    static Optional<Known> detect(Function<String, Optional<String>> read, String python) {
        if (read.apply("bin/rails").isPresent()
                && read.apply("Gemfile").orElse("").contains("rails")) {
            return Optional.of(new Known(
                    "Rails", "bundle exec rails db:prepare", Pattern.compile("db:\\w*(prepare|setup|migrate)")));
        }
        if (read.apply("manage.py").orElse("").contains("django")) {
            return Optional.of(
                    new Known("Django", python + " manage.py migrate", Pattern.compile("manage\\.py\\s+migrate")));
        }
        if (read.apply("prisma/schema.prisma").isPresent()) {
            return Optional.of(new Known(
                    "Prisma", "npx prisma migrate deploy", Pattern.compile("prisma\\s+(migrate|db\\s+push)")));
        }
        if (read.apply("artisan").isPresent()) {
            return Optional.of(
                    new Known("Laravel", "php artisan migrate --force", Pattern.compile("artisan\\s+migrate")));
        }
        if (read.apply("alembic.ini").isPresent()) {
            return Optional.of(new Known("Alembic", "alembic upgrade head", Pattern.compile("alembic\\s+upgrade")));
        }
        return Optional.empty();
    }

    /**
     * Adds a one-time card for each group of services built from code DevDesk recognises, unless the project
     * already runs that command itself. {@code read} is relative to the project folder.
     */
    public static SetupDraft offer(SetupDraft draft, Function<String, Optional<String>> read) {
        SetupDraft out = draft;
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (SetupDraft.Service s : draft.services()) {
            codeFolder(s)
                    .ifPresent(folder -> groups.computeIfAbsent(folder, f -> new ArrayList<>())
                            .add(s.name()));
        }
        for (var group : groups.entrySet()) {
            String folder = group.getKey();
            SetupDraft.Service first = byName(out, group.getValue().getFirst());
            if (isOneTime(out, first.name())) {
                continue;
            }
            Function<String, Optional<String>> here = f -> read.apply(folder.isEmpty() ? f : folder + "/" + f);
            Optional<Known> known = detect(here, python(first.command()));
            if (known.isEmpty() || alreadyDone(out, known.get())) {
                continue;
            }
            out = add(
                    out,
                    first.name(),
                    known.get().command(),
                    SUGGESTED + known.get().framework() + " app",
                    "Added because " + first.name() + " is a " + known.get().framework() + " app: "
                            + known.get().framework() + " apps create their database tables with this command"
                            + " before they work. It runs before " + String.join(" and ", group.getValue())
                            + ", on each start, and does nothing when the tables are there. If the project's"
                            + " docs name another first command, put that one here; delete the card if you"
                            + " don't want it.");
        }
        return out;
    }

    /**
     * A one-time card that runs {@code command} in a fresh copy of {@code service}: same image or folder, settings,
     * volumes and network, no ports. Every service built from the same code waits until it finished OK. This is
     * also how a command the user pastes is remembered.
     */
    public static SetupDraft add(SetupDraft draft, String service, String command, String note) {
        return add(draft, service, command, "one-time command in a copy of " + service, note);
    }

    /** The one-time cards DevDesk suggested by itself, which the user hasn't confirmed yet. */
    public static List<SetupDraft.Service> suggested(SetupDraft draft) {
        return draft.services().stream()
                .filter(s -> s.origin().startsWith(SUGGESTED))
                .toList();
    }

    /** The draft without that card, and without anything waiting for it. */
    public static SetupDraft without(SetupDraft draft, String name) {
        List<SetupDraft.Service> services = new ArrayList<>();
        for (SetupDraft.Service s : draft.services()) {
            if (s.name().equals(name)) {
                continue;
            }
            Map<String, String> deps = new LinkedHashMap<>(s.dependsOn());
            deps.remove(name);
            services.add(new SetupDraft.Service(
                    s.name(),
                    s.kind(),
                    s.command(),
                    s.directory(),
                    s.image(),
                    s.ports(),
                    s.volumes(),
                    s.environment(),
                    deps,
                    s.health(),
                    s.restart(),
                    s.origin(),
                    s.notes(),
                    s.extras()));
        }
        return new SetupDraft(draft.name(), draft.envFiles(), services, draft.notes(), draft.kept());
    }

    private static SetupDraft add(SetupDraft draft, String service, String command, String origin, String note) {
        SetupDraft.Service from = byName(draft, service);
        Set<String> names = new java.util.HashSet<>();
        draft.services().forEach(s -> names.add(s.name()));
        String name = service + "-prepare";
        for (int n = 2; names.contains(name); n++) {
            name = service + "-prepare-" + n;
        }
        Map<String, String> extras = new LinkedHashMap<>();
        // The card's own way of running (its health timing, the other kind's command) isn't the copy's.
        from.extras().forEach((k, v) -> {
            if (!k.startsWith("health.") && !k.startsWith("native.") && !k.startsWith("container.")) {
                extras.put(k, v);
            }
        });
        Map<String, String> dependsOn = new LinkedHashMap<>(from.dependsOn());
        var card = new SetupDraft.Service(
                name,
                from.kind(),
                command.strip(),
                from.directory(),
                from.image(),
                List.of(), // no ports: the app itself may be running and holding them
                from.volumes(),
                from.environment(),
                dependsOn,
                SetupDraft.Health.PROCESS,
                "no",
                origin,
                note.isEmpty() ? List.of() : List.of(note),
                extras);
        Optional<String> code = codeFolder(from);
        String job = name;
        List<SetupDraft.Service> services = new ArrayList<>();
        for (SetupDraft.Service s : draft.services()) {
            boolean sameCode = s.name().equals(service)
                    || code.isPresent() && code.equals(codeFolder(s)) && !isOneTime(draft, s.name());
            if (sameCode) {
                Map<String, String> deps = new LinkedHashMap<>(s.dependsOn());
                deps.put(job, "completed_successfully");
                s = new SetupDraft.Service(
                        s.name(),
                        s.kind(),
                        s.command(),
                        s.directory(),
                        s.image(),
                        s.ports(),
                        s.volumes(),
                        s.environment(),
                        deps,
                        s.health(),
                        s.restart(),
                        s.origin(),
                        s.notes(),
                        s.extras());
            }
            services.add(s);
        }
        services.add(card);
        return new SetupDraft(draft.name(), draft.envFiles(), services, draft.notes(), draft.kept());
    }

    /** The folder whose code a service runs: a built container's build context, or a command's folder. */
    private static Optional<String> codeFolder(SetupDraft.Service s) {
        if (s.isContainer()) {
            return Optional.ofNullable(s.extras().get("build"))
                    .map(b -> b.equals(".") ? "" : b.replaceFirst("^\\./", "").replaceFirst("/$", ""));
        }
        return Optional.of(s.directory());
    }

    /** A service others wait for until it finished: it is itself a one-time job (a migrate service). */
    private static boolean isOneTime(SetupDraft draft, String name) {
        return draft.services().stream()
                .anyMatch(s -> "completed_successfully".equals(s.dependsOn().get(name)));
    }

    private static boolean alreadyDone(SetupDraft draft, Known known) {
        return draft.services().stream()
                .anyMatch(s -> known.done().matcher(s.command()).find());
    }

    /** The Python the service itself uses ({@code .venv/bin/python}), so the packages it sees are there. */
    private static String python(String command) {
        String first = command.strip().split("\\s+")[0];
        return first.matches("\\S*python3?") ? first : "python";
    }

    private static SetupDraft.Service byName(SetupDraft draft, String name) {
        return draft.services().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no service \"" + name + "\" in this setup"));
    }
}
