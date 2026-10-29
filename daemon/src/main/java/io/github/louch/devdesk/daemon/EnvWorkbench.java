package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.config.EnvFile;
import io.github.louch.devdesk.core.config.EnvFileEdit;
import io.github.louch.devdesk.core.setup.EnvReport;
import io.github.louch.devdesk.core.setup.ExistingSetup;
import io.github.louch.devdesk.core.setup.SetupDraft;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The .env editor of the open project: every env file, which service uses which variable, what's missing. Secret-looking
 * values stay in the engine until the user clicks "show" (secrets never leak); edits keep the file's comments and order.
 */
final class EnvWorkbench {

    /** Files this editor may touch: .env, .env.demo, .env.local… never anything outside the project folder. */
    private static final Pattern ENV_NAME = Pattern.compile("\\.env[A-Za-z0-9._-]*");

    private final EngineSession session;
    private final Map<String, String> environment;

    EnvWorkbench(EngineSession session, Map<String, String> environment) {
        this.session = session;
        this.environment = environment;
    }

    EngineSession.Answer overview() {
        Optional<Path> root = root();
        if (root.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        return new EngineSession.Answer(200, report(root.get()));
    }

    private Map<String, Object> report(Path root) {
        Optional<SetupDraft> draft = draft(root);
        Set<String> listed = new LinkedHashSet<>(draft.map(SetupDraft::envFiles).orElse(List.of()));
        Set<String> names = new LinkedHashSet<>(listed);
        try (Stream<Path> files = Files.list(root)) {
            files.map(p -> p.getFileName().toString())
                    .filter(n -> ENV_NAME.matcher(n).matches())
                    .sorted()
                    .forEach(names::add);
        } catch (IOException e) {
            // the folder can't be listed: the files devdesk.yaml lists are still shown
        }
        // Who uses what, from devdesk.yaml's ${VAR}s, and what's missing: the wizard's settings report.
        EnvReport.Result report = draft.map(d -> EnvReport.of(
                        new SetupDraft(d.name(), List.copyOf(names), d.services(), d.notes()),
                        f -> read(root, f),
                        environment.keySet()))
                .orElse(new EnvReport.Result(List.of(), List.of(), List.of()));
        List<Map<String, Object>> files = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", name);
            f.put("exists", Files.isRegularFile(root.resolve(name)));
            f.put("example", name.matches(".*\\.(example|sample|template|dist)$"));
            f.put("everyService", listed.contains(name)); // env_files: every service gets these
            f.put(
                    "variables",
                    report.variables().stream()
                            .filter(v -> v.file().equals(name))
                            .map(v -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("key", v.key());
                                m.put("value", v.value().orElse(null));
                                m.put("secret", v.secret());
                                m.put("usedBy", v.usedBy());
                                return m;
                            })
                            .toList());
            files.add(f);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("files", files);
        answer.put(
                "missing",
                report.missing().stream()
                        .map(x -> Map.of("key", x.key(), "reason", x.reason()))
                        .toList());
        answer.put("warnings", report.warnings());
        answer.put("running", session.running());
        return answer;
    }

    /** Sets (or adds) one variable, then reloads the project so the next start uses it. */
    EngineSession.Answer set(Map<?, ?> body) {
        return change(body, true);
    }

    EngineSession.Answer delete(Map<?, ?> body) {
        return change(body, false);
    }

    /** The real value of one variable: only on an explicit click, like the service drawer's "reveal". */
    EngineSession.Answer reveal(Map<?, ?> body) {
        Optional<Path> file = file(body.get("file"));
        if (file.isEmpty() || !(body.get("key") instanceof String key)) {
            return new EngineSession.Answer(400, ApiJson.error("send {\"file\": \".env\", \"key\": \"DB_PASSWORD\"}"));
        }
        String value = read(file.get().getParent(), file.get().getFileName().toString())
                .map(EnvFile::parse)
                .map(m -> m.get(key))
                .orElse(null);
        return value == null
                ? new EngineSession.Answer(
                        404, ApiJson.error(key + " isn't set in " + file.get().getFileName()))
                : new EngineSession.Answer(200, Map.of("key", key, "value", value));
    }

    private EngineSession.Answer change(Map<?, ?> body, boolean set) {
        Optional<Path> file = file(body.get("file"));
        if (file.isEmpty()
                || !(body.get("key") instanceof String key)
                || !EnvFileEdit.KEY.matcher(key).matches()
                || (set && !(body.get("value") instanceof String))) {
            return new EngineSession.Answer(
                    400,
                    ApiJson.error(
                            "send {\"file\": \".env\", \"key\": \"NAME\", \"value\": \"...\"} (names: letters, digits, _)"));
        }
        Path target = file.get();
        try {
            String before = Files.exists(target) ? Files.readString(target) : "";
            String after =
                    set ? EnvFileEdit.set(before, key, (String) body.get("value")) : EnvFileEdit.remove(before, key);
            Path temp = Files.createTempFile(target.getParent(), ".env-", ".tmp");
            Files.writeString(temp, after, StandardCharsets.UTF_8);
            // A new file holds secrets: only you may read it. An existing one keeps the permissions it had.
            Files.setPosixFilePermissions(
                    temp,
                    Files.exists(target)
                            ? Files.getPosixFilePermissions(target)
                            : PosixFilePermissions.fromString(
                                    ServiceEnvironment.looksSecret(key) ? "rw-------" : "rw-r--r--"));
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IllegalArgumentException e) {
            return new EngineSession.Answer(400, ApiJson.error(e.getMessage()));
        } catch (IOException e) {
            return new EngineSession.Answer(500, ApiJson.error("could not write " + target + ": " + e.getMessage()));
        }
        // devdesk.yaml reads env files when the project opens: reopen so the next start sees the change.
        boolean running = session.running();
        if (!running) {
            session.open(target.getParent());
        }
        Map<String, Object> answer = report(target.getParent());
        answer.put("restartNeeded", running);
        return new EngineSession.Answer(200, answer);
    }

    private Optional<Path> root() {
        return session.current().map(c -> c.project().root());
    }

    /** {@code name} inside the open project, if it's an .env-style name. */
    private Optional<Path> file(Object name) {
        if (!(name instanceof String n) || !ENV_NAME.matcher(n).matches()) {
            return Optional.empty();
        }
        return root().map(r -> r.resolve(n));
    }

    private static Optional<SetupDraft> draft(Path root) {
        try {
            return Optional.of(ExistingSetup.read(
                    Files.readString(root.resolve(ConfigParser.FILE_NAME)),
                    root.getFileName().toString()));
        } catch (IOException | ConfigException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> read(Path root, String name) {
        try {
            Path f = root.resolve(name).normalize();
            return f.startsWith(root) && Files.isRegularFile(f) ? Optional.of(Files.readString(f)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
