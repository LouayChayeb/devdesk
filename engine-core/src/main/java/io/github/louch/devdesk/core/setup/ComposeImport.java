package io.github.louch.devdesk.core.setup;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.config.YamlNode;
import io.github.louch.devdesk.core.config.YamlReader;
import io.github.louch.devdesk.core.container.ContainerRuntime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a docker-compose.yml into wizard cards. Images become containers. Services compose builds from a Dockerfile
 * become containers too, built like {@code docker compose build}, so nothing has to be installed on this machine; the
 * command to run one natively instead (found by {@code detectApp}) stays on the card, one switch away. What can't be
 * mapped is said in a note, never dropped silently.
 */
public final class ComposeImport {

    /**
     * What the scan found in one folder: how to start the app there, and the port it usually uses.
     *
     * @param notes what to do before it can start ("run npm install once")
     */
    public record DetectedApp(
            String command, Optional<Integer> port, Optional<String> healthUrl, String origin, List<String> notes) {}

    /** The imported cards, the .env files compose reads, and notes for the whole file. */
    public record Result(List<SetupDraft.Service> services, List<String> envFiles, List<String> notes) {}

    /** Starts the note when the file isn't valid YAML; the scan puts the file's name in front. */
    static final String UNREADABLE = "could not be read, so nothing was imported from it: ";

    private static final Pattern DURATION = Pattern.compile("\\d+(ms|s|m|h)");
    private static final Pattern PORT =
            Pattern.compile("(?:(\\d+\\.\\d+\\.\\d+\\.\\d+):)?(\\d+)(?::(\\d+))?(/tcp|/udp)?");

    private final String project;
    private final String composeProject;
    private final Function<String, Optional<DetectedApp>> detectApp;
    private final List<String> notes = new ArrayList<>();
    /** Top-level secrets: name → the file it comes from (compose mounts it at /run/secrets/name). */
    private final Map<String, String> secretFiles = new LinkedHashMap<>();
    /** Services whose usual port DevDesk publishes because compose kept them on its own network. */
    private final Map<String, Integer> addedPorts = new LinkedHashMap<>();

    private final Set<String> envFiles = new LinkedHashSet<>();

    private ComposeImport(String project, String composeProject, Function<String, Optional<DetectedApp>> detectApp) {
        this.project = project;
        this.composeProject = composeProject;
        this.detectApp = detectApp;
    }

    /**
     * @param project the DevDesk project name (containers are called devdesk-{project}-{service})
     * @param folderName the folder's name: compose's default project name, used in its volume names
     * @param detectApp how to start the app in a folder relative to the project ("api"), if recognisable
     */
    public static Result read(
            String yaml, String project, String folderName, Function<String, Optional<DetectedApp>> detectApp) {
        return read(yaml, Optional.empty(), project, folderName, detectApp);
    }

    /**
     * @param override compose.override.yml, which docker compose reads on top of the file (Plausible publishes its
     *     port there): merged the same way before anything is mapped
     */
    public static Result read(
            String yaml,
            Optional<String> override,
            String project,
            String folderName,
            Function<String, Optional<DetectedApp>> detectApp) {
        YamlNode top;
        try {
            top = YamlReader.read(yaml);
            if (override.isPresent()) {
                top = merge(top, YamlReader.read(override.get()), "");
            }
        } catch (ConfigException e) {
            return new Result(List.of(), List.of(), List.of(UNREADABLE + e.getMessage()));
        }
        String composeProject = entry(top, "name")
                .flatMap(ComposeImport::text)
                .orElse(folderName)
                .toLowerCase()
                .replaceAll("[^a-z0-9_-]", "");
        return new ComposeImport(project, composeProject, detectApp).services(top);
    }

    private Result services(YamlNode top) {
        entry(top, "secrets")
                .ifPresent(all -> mapping(all)
                        .forEach((name, def) -> entry(def, "file")
                                .flatMap(ComposeImport::text)
                                .ifPresent(f ->
                                        secretFiles.put(name, f.startsWith("/") || f.startsWith(".") ? f : "./" + f))));
        Map<String, YamlNode.Mapping> raw = new LinkedHashMap<>();
        entry(top, "services")
                .ifPresent(s -> mapping(s).forEach((name, node) -> {
                    if (node instanceof YamlNode.Mapping m) {
                        raw.put(name, m);
                    }
                }));
        if (raw.isEmpty()) {
            notes.add("docker-compose.yml has no services.");
        }
        // docker compose only starts services without profiles; the others are alternatives to choose from
        // (hoppscotch: "default", "app", "deprecated"... which "would conflict on ports" if mixed). All of them at once
        // made 12 cards fighting over the same ports. A profile named "default" is the file's own usual choice.
        List<String> leftOut = new ArrayList<>();
        raw.entrySet().removeIf(e -> {
            List<String> profiles = profiles(e.getValue());
            boolean out = !profiles.isEmpty() && !profiles.contains("default");
            if (out) {
                leftOut.add(e.getKey() + " (" + String.join(", ", profiles) + ")");
            }
            return out;
        });
        if (!leftOut.isEmpty()) {
            notes.add("Left out because docker compose doesn't start them by default (they're in compose profiles): "
                    + String.join(", ", leftOut) + ". Add one with Add service if you need it.");
        }
        // Container port → host port per service, to point commands at containers through the host.
        Map<String, Map<Integer, Integer>> published = new LinkedHashMap<>();
        raw.forEach((name, s) -> published.put(name, portMap(ports(s, name, new ArrayList<>()))));
        // A database compose keeps on its own network (no ports:) can't be reached from apps on this machine:
        // publish its usual port on 127.0.0.1, said in a note on its card.
        raw.forEach((name, s) -> {
            Optional<Integer> usual =
                    entry(s, "image").flatMap(ComposeImport::text).flatMap(KnownImages::usualPort);
            if (published.get(name).isEmpty() && usual.isPresent()) {
                published.put(name, Map.of(usual.get(), usual.get()));
                addedPorts.put(name, usual.get());
            }
        });

        List<SetupDraft.Service> services = new ArrayList<>();
        raw.forEach((name, s) -> services.add(service(serviceName(name), s, published)));
        // A kept service may name one that was left out (a "default" migrate waiting for a "deprecated" db).
        Set<String> kept = new HashSet<>();
        services.forEach(sv -> kept.add(sv.name()));
        services.replaceAll(sv -> {
            Map<String, String> deps = new LinkedHashMap<>(sv.dependsOn());
            return deps.keySet().removeIf(d -> !kept.contains(d)) ? sv.withDependsOn(deps) : sv;
        });
        return new Result(services, List.copyOf(envFiles), List.copyOf(notes));
    }

    /** The compose profiles a service belongs to; empty for a service compose always starts. */
    private static List<String> profiles(YamlNode.Mapping service) {
        return entry(service, "profiles")
                .filter(p -> p instanceof YamlNode.Sequence)
                .map(p -> ((YamlNode.Sequence) p)
                        .items().stream()
                                .map(ComposeImport::text)
                                .flatMap(Optional::stream)
                                .toList())
                .orElse(List.of());
    }

    /** Keys whose value the override replaces as a whole, as compose does: a command isn't added to, it's changed. */
    private static final Set<String> REPLACED = Set.of("command", "entrypoint", "test", "image", "build");

    /**
     * Compose's rule for an override file: mappings merge key by key, lists like ports and volumes add up (without
     * repeats), and anything else the override says wins.
     */
    static YamlNode merge(YamlNode base, YamlNode over, String key) {
        if (REPLACED.contains(key)) {
            return over;
        }
        if (base instanceof YamlNode.Mapping b && over instanceof YamlNode.Mapping o) {
            Map<String, YamlNode.Entry> entries = new LinkedHashMap<>(b.entries());
            o.entries()
                    .forEach((k, e) -> entries.merge(
                            k,
                            e,
                            (old, add) -> new YamlNode.Entry(old.keyLocation(), merge(old.value(), add.value(), k))));
            return new YamlNode.Mapping(entries, b.location());
        }
        if (base instanceof YamlNode.Sequence b && over instanceof YamlNode.Sequence o) {
            List<YamlNode> items = new ArrayList<>(b.items());
            for (YamlNode item : o.items()) {
                boolean seen = item instanceof YamlNode.Scalar sc
                        && items.stream()
                                .anyMatch(i -> i instanceof YamlNode.Scalar x
                                        && x.text() != null
                                        && x.text().equals(sc.text()));
                if (!seen) {
                    items.add(item);
                }
            }
            return new YamlNode.Sequence(items, b.location());
        }
        return over;
    }

    private SetupDraft.Service service(String name, YamlNode.Mapping s, Map<String, Map<Integer, Integer>> published) {
        List<String> cardNotes = new ArrayList<>();
        Optional<String> image = entry(s, "image").flatMap(ComposeImport::text);
        Optional<String> buildDir = entry(s, "build")
                .flatMap(b ->
                        b instanceof YamlNode.Mapping ? entry(b, "context").flatMap(ComposeImport::text) : text(b));
        List<String> ports = ports(s, name, cardNotes);
        Map<String, String> env = environment(s, cardNotes);
        // compose's env_file: what this service's container gets (not the whole project's).
        List<String> ownEnvFiles = new ArrayList<>();
        entry(s, "env_file")
                .ifPresent(e -> list(e).forEach(
                                f -> text(f).or(() -> entry(f, "path").flatMap(ComposeImport::text))
                                        .ifPresent(file -> ownEnvFiles.add(file.replaceFirst("^\\./", "")))));
        Map<String, String> dependsOn = dependsOn(s);
        String restart = entry(s, "restart")
                .flatMap(ComposeImport::text)
                .map(r -> switch (r.replaceAll(":.*", "")) {
                    case "always", "unless-stopped" -> "always";
                    case "on-failure" -> "on-failure";
                    default -> "no";
                })
                .orElse("no");
        // Chatwoot's rails service starts through docker/entrypoints/rails.sh: kept, or the app skips its preparation.
        Optional<String> entrypoint = entry(s, "entrypoint").map(ComposeImport::commandLine);

        Map<String, String> extras = new LinkedHashMap<>();
        if (!ownEnvFiles.isEmpty()) {
            extras.put("env_files", String.join(",", ownEnvFiles));
        }
        // Twenty's server boots for ~100 s: compose gives it 20 retries 5 s apart, and so must DevDesk.
        extras.putAll(healthTiming(s));
        entrypoint.ifPresent(e -> extras.put("entrypoint", e));
        if (buildDir.isPresent()) {
            List<String> shared = List.copyOf(cardNotes);
            SetupDraft.Service local =
                    onThisMachine(name, s, buildDir.get(), ports, env, dependsOn, restart, published, cardNotes);
            String dir = local.directory();
            cardNotes.clear();
            cardNotes.addAll(shared);
            // Containers by default: the project's own Dockerfile installs what it needs, nothing lands on this
            // machine. The command found for running it here stays on the card, one click away.
            extras.put("build", dir.isEmpty() ? "." : dir);
            entry(s, "build")
                    .flatMap(b -> b instanceof YamlNode.Mapping ? entry(b, "dockerfile") : Optional.empty())
                    .flatMap(ComposeImport::text)
                    .ifPresent(f -> extras.put("build.dockerfile", f));
            if (!local.command().isBlank()) {
                extras.put("native.command", local.command());
                extras.put("native.ports", String.join(",", local.ports()));
                extras.put("native.health.type", local.health().type());
                extras.put("native.health.target", local.health().target());
            }
            extras.put("native.directory", local.directory());
            // On this machine, compose names (db:5432) become 127.0.0.1 and secrets become file paths.
            local.environment().forEach((k, v) -> {
                if (!v.equals(env.get(k))) {
                    extras.put("native.env." + k, v);
                }
            });
            image = image.or(() -> Optional.of(composeProject + "-" + name));
            cardNotes.add("DevDesk builds its image from ./" + (dir.isEmpty() ? "" : dir + "/")
                    + extras.getOrDefault("build.dockerfile", "Dockerfile") + " the first time it starts, like docker"
                    + " compose build"
                    + (local.command().isBlank()
                            ? "."
                            : ". To run it on your machine instead (code changes apply right away), switch it to"
                                    + " command: DevDesk fills in \"" + local.command() + "\"."));
        }
        if (image.isEmpty()) {
            cardNotes.add("It has neither image: nor build: in docker-compose.yml. Choose an image or a command.");
        }
        Integer added = addedPorts.get(name);
        if (added != null) {
            ports = List.of(added + ":" + added);
            cardNotes.add("docker-compose.yml keeps it on its own network (no ports:). DevDesk publishes its usual"
                    + " port " + added + " on 127.0.0.1, so the apps on your machine can reach it.");
        }
        String command = entry(s, "command").map(ComposeImport::commandLine).orElse("");
        String container = ContainerRuntime.containerName(project, name);
        SetupDraft.Health health = health(s, image.orElse(""), container, ports);
        if (health.equals(SetupDraft.Health.PROCESS) && ports.size() > 1) {
            cardNotes.add("It publishes " + ports.size() + " ports and compose gives it no health check, so DevDesk"
                    + " only checks that it runs. To check it answers, give it a tcp or http check on the port that"
                    + " serves.");
        }
        return new SetupDraft.Service(
                name,
                "container",
                command,
                "",
                image.orElse(""),
                ports,
                withSecrets(volumes(s), s, cardNotes),
                env,
                dependsOn,
                health,
                restart,
                "docker-compose.yml",
                cardNotes,
                extras);
    }

    /**
     * How a service compose builds from a Dockerfile runs on this machine instead (how people develop day to day),
     * found by {@code detectApp}. Its notes go to {@code cardNotes}.
     */
    private SetupDraft.Service onThisMachine(
            String name,
            YamlNode.Mapping s,
            String buildDir,
            List<String> ports,
            Map<String, String> env,
            Map<String, String> dependsOn,
            String restart,
            Map<String, Map<Integer, Integer>> published,
            List<String> cardNotes) {
        String cleaned = buildDir.replaceFirst("^\\./", "").replaceFirst("/$", "");
        String dir = cleaned.equals(".") ? "" : cleaned;
        Optional<DetectedApp> app = detectApp.apply(dir);
        Optional<String> composeCommand = entry(s, "command").map(ComposeImport::commandLine);
        if (composeCommand.isPresent()
                && app.map(a -> a.origin().matches(".*Dockerfile (CMD|ENTRYPOINT)"))
                        .orElse(true)) {
            // Compose's command overrides the Dockerfile's CMD, so it's the more faithful of the two.
            app = Optional.of(new DetectedApp(
                    composeCommand.get(),
                    app.flatMap(DetectedApp::port),
                    Optional.empty(),
                    "its command: in docker-compose.yml",
                    List.of("It's the command compose runs inside the image: it assumes what the image installs"
                            + " is installed here too, and compose names in it (like db:5432) need 127.0.0.1"
                            + " and the published port.")));
        }
        List<Integer> hostPorts = ports.stream()
                .map(p -> portMap(List.of(p)).values().stream().findFirst().orElse(-1))
                .filter(p -> p > 0)
                .toList();
        List<String> commandPorts = hostPorts.isEmpty()
                ? app.flatMap(DetectedApp::port)
                        .map(p -> List.of(String.valueOf(p)))
                        .orElse(List.of())
                : hostPorts.stream().map(String::valueOf).toList();
        cardNotes.add(app.map(a -> "docker-compose.yml builds it from a Dockerfile in ./" + dir
                        + ". DevDesk runs it on your machine instead (" + a.origin() + "), so code changes "
                        + "apply right away. Check the command.")
                .orElse("docker-compose.yml builds it from a Dockerfile in ./" + dir
                        + ". To run it on your machine, type the command that starts it."));
        app.ifPresent(a -> cardNotes.addAll(a.notes()));
        Map<String, String> pointed = pointAtHost(name, env, published, cardNotes);
        for (String secret : secrets(s)) {
            String file = secretFiles.get(secret);
            // On this machine there's no /run/secrets: a setting that names it gets the file instead.
            List<String> keys = file == null
                    ? List.of()
                    : pointed.entrySet().stream()
                            .filter(e -> e.getValue().equals("/run/secrets/" + secret))
                            .map(Map.Entry::getKey)
                            .toList();
            keys.forEach(k -> pointed.put(k, fromServiceDir(dir, file)));
            cardNotes.add(
                    keys.isEmpty()
                            ? "Compose gives it the secret \"" + secret + "\" at /run/secrets/" + secret
                                    + "; on your"
                                    + " machine it's the file "
                                    + Optional.ofNullable(file).orElse("(not a file)")
                                    + ": point the setting that reads it there."
                            : String.join(", ", keys) + " named the secret \"" + secret + "\" at /run/secrets/"
                                    + secret
                                    + ", which only exists inside compose's container; it now names the file "
                                    + file
                                    + " instead.");
        }
        SetupDraft.Health health = app.flatMap(DetectedApp::healthUrl)
                .map(SetupDraft.Health::http)
                .orElse(
                        commandPorts.isEmpty()
                                ? SetupDraft.Health.PROCESS
                                : SetupDraft.Health.tcp(Integer.parseInt(commandPorts.getFirst())));
        return new SetupDraft.Service(
                name,
                "command",
                app.map(DetectedApp::command).orElse(""),
                dir,
                "",
                commandPorts,
                List.of(),
                pointed,
                dependsOn,
                health,
                restart,
                "docker-compose.yml",
                cardNotes);
    }

    /** "8080:80", "127.0.0.1:8080:80", {target: 80, published: 8080}. A port with no host side is reported. */
    private List<String> ports(YamlNode.Mapping s, String service, List<String> cardNotes) {
        List<String> out = new ArrayList<>();
        entry(s, "ports")
                .ifPresent(p -> list(p).forEach(item -> {
                    Optional<String> spec = item instanceof YamlNode.Mapping m
                            ? entry(m, "published")
                                    .flatMap(ComposeImport::text)
                                    .flatMap(pub -> entry(m, "target")
                                            .flatMap(ComposeImport::text)
                                            .map(t -> entry(m, "host_ip")
                                                            .flatMap(ComposeImport::text)
                                                            .map(ip -> ip + ":")
                                                            .orElse("")
                                                    + pub + ":" + t))
                            : text(item);
                    Matcher m = spec.map(PORT::matcher).filter(Matcher::matches).orElse(null);
                    if (m != null && m.group(1) == null && m.group(3) == null) {
                        // "8080" alone: compose picks a random host port. DevDesk needs a fixed one: the same number.
                        out.add(m.group(2) + ":" + m.group(2));
                        cardNotes.add("Port " + m.group(2) + ": compose publishes it on a random host port; DevDesk"
                                + " uses " + m.group(2) + ":" + m.group(2) + " (a fixed one). Change it if "
                                + m.group(2) + " is taken.");
                        return;
                    }
                    if (m == null || m.group(3) == null) {
                        cardNotes.add(
                                "Port " + spec.orElse("?") + " was skipped: DevDesk needs a fixed \"host:container\" "
                                        + "port like \"8080:80\" (no ranges, no random host port).");
                        return;
                    }
                    out.add((m.group(1) == null ? "" : m.group(1) + ":") + m.group(2) + ":" + m.group(3)
                            + ("/udp".equals(m.group(4)) ? "/udp" : ""));
                }));
        return out;
    }

    private static Map<Integer, Integer> portMap(List<String> mappings) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (String p : mappings) {
            String[] parts = p.split(":");
            if (parts.length >= 2 && !p.endsWith("/udp")) { // TCP ports only: what apps and checks connect to
                map.put(Integer.parseInt(parts[parts.length - 1]), Integer.parseInt(parts[parts.length - 2]));
            }
        }
        return map;
    }

    /** {@code KEY: value} or {@code - KEY=value}. A bare {@code - KEY} (copied from your shell) is reported. */
    private static Map<String, String> environment(YamlNode.Mapping s, List<String> cardNotes) {
        Map<String, String> env = new LinkedHashMap<>();
        entry(s, "environment").ifPresent(e -> {
            if (e instanceof YamlNode.Mapping m) {
                m.entries().forEach((k, v) -> env.put(k, text(v.value()).orElse("")));
            } else {
                list(e).forEach(item -> text(item).ifPresent(line -> {
                    int eq = line.indexOf('=');
                    if (eq > 0) {
                        env.put(line.substring(0, eq), line.substring(eq + 1));
                    } else {
                        cardNotes.add(line + " takes its value from your shell in docker-compose.yml. Give it a value "
                                + "here, or put it in .env.");
                    }
                }));
            }
        });
        return env;
    }

    private static Map<String, String> dependsOn(YamlNode.Mapping s) {
        Map<String, String> deps = new LinkedHashMap<>();
        entry(s, "depends_on").ifPresent(d -> {
            if (d instanceof YamlNode.Mapping m) {
                m.entries()
                        .forEach((dep, e) -> deps.put(
                                serviceName(dep),
                                entry(e.value(), "condition")
                                        .flatMap(ComposeImport::text)
                                        .map(c -> switch (c) {
                                            case "service_healthy" -> "healthy";
                                            case "service_completed_successfully" -> "completed_successfully";
                                            default -> "started";
                                        })
                                        .orElse("started")));
            } else {
                list(d).forEach(item -> text(item).ifPresent(dep -> deps.put(serviceName(dep), "started")));
            }
        });
        return deps;
    }

    /**
     * Compose mounts each secret a service lists at /run/secrets/<name>, read-only: DevDesk does the same with a bind
     * mount of its file. A secret that isn't a file (environment:, external:) is named in a note instead.
     */
    private List<String> withSecrets(List<String> volumes, YamlNode.Mapping s, List<String> cardNotes) {
        List<String> out = new ArrayList<>(volumes);
        for (String secret : secrets(s)) {
            String file = secretFiles.get(secret);
            if (file == null) {
                cardNotes.add("Its secret \"" + secret + "\" isn't a file in docker-compose.yml (environment or"
                        + " external secrets aren't imported): mount it at /run/secrets/" + secret + " yourself.");
            } else {
                out.add(file + ":/run/secrets/" + secret + ":ro");
            }
        }
        return out;
    }

    /** A path relative to the compose folder, as seen from a service's directory (where its command runs). */
    static String fromServiceDir(String dir, String file) {
        if (file.startsWith("/")) {
            return file;
        }
        String clean = file.replaceFirst("^\\./", "");
        return dir.isEmpty() ? "./" + clean : "../".repeat(dir.split("/").length) + clean;
    }

    /** The secrets a service lists: "- db-password" or "- source: db-password". */
    private static List<String> secrets(YamlNode.Mapping s) {
        List<String> out = new ArrayList<>();
        entry(s, "secrets")
                .ifPresent(list -> list(list)
                        .forEach(item -> (item instanceof YamlNode.Mapping m
                                        ? entry(m, "source").flatMap(ComposeImport::text)
                                        : text(item))
                                .ifPresent(out::add)));
        return out;
    }

    /**
     * Named volumes keep compose's real name ({project}_{volume}), so the data you already have is still there.
     * Don't run compose and DevDesk on it at the same time.
     */
    private List<String> volumes(YamlNode.Mapping s) {
        List<String> out = new ArrayList<>();
        entry(s, "volumes")
                .ifPresent(v -> list(v).forEach(item -> {
                    Optional<String> spec = item instanceof YamlNode.Mapping m
                            ? entry(m, "source")
                                    .flatMap(ComposeImport::text)
                                    .flatMap(src -> entry(m, "target")
                                            .flatMap(ComposeImport::text)
                                            .map(t -> src + ":" + t))
                            : text(item);
                    spec.ifPresent(text -> {
                        int colon = text.indexOf(':');
                        String source = colon < 0 ? text : text.substring(0, colon);
                        boolean named = colon > 0
                                && !source.startsWith(".")
                                && !source.startsWith("/")
                                && !source.startsWith("~");
                        out.add(named ? composeProject + "_" + text : text);
                    });
                }));
        return out;
    }

    /** Compose's healthcheck run with docker exec; else what the image is known to need; else the first port. */
    private static SetupDraft.Health health(YamlNode.Mapping s, String image, String container, List<String> ports) {
        Optional<YamlNode> check = entry(s, "healthcheck");
        Optional<String> test = check.flatMap(h -> entry(h, "test")).flatMap(t -> {
            if (t instanceof YamlNode.Sequence seq && !seq.items().isEmpty()) {
                List<String> words =
                        seq.items().stream().map(i -> text(i).orElse("")).toList();
                return switch (words.getFirst()) {
                    case "NONE" -> Optional.empty();
                    case "CMD-SHELL" -> Optional.of("sh -c " + quote(String.join(" ", words.subList(1, words.size()))));
                    case "CMD" ->
                        Optional.of(String.join(
                                " ",
                                words.subList(1, words.size()).stream()
                                        .map(ComposeImport::quoteIfNeeded)
                                        .toList()));
                    default ->
                        Optional.of(String.join(
                                " ",
                                words.stream().map(ComposeImport::quoteIfNeeded).toList()));
                };
            }
            return text(t).map(cmd -> "sh -c " + quote(cmd));
        });
        String startPeriod = check.flatMap(h -> entry(h, "start_period"))
                .flatMap(ComposeImport::text)
                .filter(d -> DURATION.matcher(d).matches())
                .orElse("40s");
        if (test.isPresent()) {
            return SetupDraft.Health.command("docker exec " + container + " " + test.get(), startPeriod);
        }
        // No healthcheck in compose: a port only when there is exactly one to check. With several (jaeger publishes
        // 5778, 16686, 14268, 9411), the first one is a guess that was wrong: "running" is the honest check.
        return KnownImages.health(image, container, ports)
                .orElse(
                        ports.size() != 1
                                ? SetupDraft.Health.PROCESS
                                : SetupDraft.Health.tcp(portMap(List.of(ports.getFirst()))
                                        .values()
                                        .iterator()
                                        .next()));
    }

    /** compose's healthcheck interval, timeout and retries, as the card's health timing ("health.interval"…). */
    private static Map<String, String> healthTiming(YamlNode.Mapping s) {
        Map<String, String> timing = new LinkedHashMap<>();
        entry(s, "healthcheck").ifPresent(h -> {
            for (String key : List.of("interval", "timeout")) {
                entry(h, key)
                        .flatMap(ComposeImport::text)
                        .filter(d -> DURATION.matcher(d).matches())
                        .ifPresent(d -> timing.put("health." + key, d));
            }
            entry(h, "retries")
                    .flatMap(ComposeImport::text)
                    .filter(r -> r.matches("\\d{1,3}"))
                    .ifPresent(r -> timing.put("health.retries", r));
        });
        return timing;
    }

    /**
     * A command on this machine can't resolve compose names like "db": "db:5432" becomes "127.0.0.1:55432" (the host
     * port compose publishes), and a value that is just "db" becomes "127.0.0.1".
     */
    private static Map<String, String> pointAtHost(
            String self,
            Map<String, String> env,
            Map<String, Map<Integer, Integer>> published,
            List<String> cardNotes) {
        Map<String, String> out = new LinkedHashMap<>();
        env.forEach((key, value) -> {
            String v = value;
            for (var other : published.entrySet()) {
                String name = other.getKey();
                if (name.equals(self)) {
                    continue;
                }
                for (var port : other.getValue().entrySet()) {
                    v = v.replaceAll(
                            "(?<![\\w.-])" + Pattern.quote(name) + ":" + port.getKey() + "(?!\\d)",
                            "127.0.0.1:" + port.getValue());
                }
                // Only for a setting that names a host: POSTGRES_USER: umami is a user name, not the "umami" service.
                if (v.equals(name) && hostSetting(key)) {
                    v = "127.0.0.1";
                }
            }
            if (!v.equals(value)) {
                cardNotes.add(key + " pointed at another compose service by name; it now uses 127.0.0.1 and the "
                        + "published port, because this runs on your machine.");
            }
            out.put(key, v);
        });
        return out;
    }

    /** Settings whose value is a host name: DB_HOST, REDIS_URL, KAFKA_BOOTSTRAP_SERVERS, API_ENDPOINT... */
    private static final Pattern HOST_SETTING =
            Pattern.compile("(?i).*(HOST|HOSTNAME|HOSTS|SERVER|SERVERS|ADDR|ADDRESS|URL|URI|DSN|ENDPOINT|BROKERS?)$");

    static boolean hostSetting(String key) {
        return HOST_SETTING.matcher(key).matches();
    }

    private static String commandLine(YamlNode node) {
        return node instanceof YamlNode.Sequence seq
                ? String.join(
                        " ",
                        seq.items().stream()
                                .map(i -> quoteIfNeeded(text(i).orElse("")))
                                .toList())
                : text(node).orElse("");
    }

    /** DevDesk service names are lowercase letters, digits, - and _. */
    static String serviceName(String composeName) {
        String n = composeName.toLowerCase().replaceAll("[^a-z0-9_-]", "-").replaceAll("^[-_]+", "");
        return n.isEmpty() ? "service" : n;
    }

    static String quoteIfNeeded(String word) {
        return word.matches("[A-Za-z0-9_./:=@%+,-]+") ? word : quote(word);
    }

    /** Single quotes for sh: nothing inside is special, and a ' is written as '\''. */
    static String quote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    // ---- small YAML helpers ----

    static Optional<YamlNode> entry(YamlNode node, String key) {
        return node instanceof YamlNode.Mapping m && m.entries().containsKey(key)
                ? Optional.of(m.entries().get(key).value())
                : Optional.empty();
    }

    static Map<String, YamlNode> mapping(YamlNode node) {
        Map<String, YamlNode> out = new LinkedHashMap<>();
        if (node instanceof YamlNode.Mapping m) {
            m.entries().forEach((k, e) -> out.put(k, e.value()));
        }
        return out;
    }

    /** A list, or a single value as a list of one (compose allows both in many places). */
    static List<YamlNode> list(YamlNode node) {
        return node instanceof YamlNode.Sequence s ? s.items() : List.of(node);
    }

    static Optional<String> text(YamlNode node) {
        return node instanceof YamlNode.Scalar s && !s.isNull() ? Optional.of(s.text()) : Optional.empty();
    }
}
