package io.github.louch.devdesk.core.config;

import io.github.louch.devdesk.core.graph.DependencyCycleException;
import io.github.louch.devdesk.core.graph.DependencyGraph;
import io.github.louch.devdesk.core.project.Dependency;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import io.github.louch.devdesk.core.project.Profile;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.RestartPolicy;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.TaskSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turns devdesk.yaml into a validated {@link ProjectSpec}. Collects every problem before failing, and points each one
 * at a line and column with a hint on how to fix it.
 */
public final class ConfigParser {

    public static final String FILE_NAME = "devdesk.yaml";

    private static final Set<String> TOP_KEYS = Set.of("version", "name", "env_files", "services", "profiles", "tasks");
    private static final Set<String> TASK_KEYS =
            Set.of("command", "directory", "environment", "needs", "description", "confirm");
    private static final Set<String> PROFILE_KEYS = Set.of("env_files", "environment", "services");
    private static final Set<String> SERVICE_KEYS = Set.of(
            "command",
            "directory",
            "environment",
            "depends_on",
            "ports",
            "health",
            "restart",
            "stop_grace_period",
            "container",
            "watch",
            "test",
            "per_branch",
            "sleep_after");
    private static final Set<String> HEALTH_KEYS = Set.of(
            "type",
            "host",
            "port",
            "url",
            "expect_status",
            "command",
            "interval",
            "timeout",
            "retries",
            "start_period");
    private static final Set<String> CONTAINER_KEYS = Set.of("image", "ports", "volumes", "environment", "command");
    private static final Pattern PORT_MAPPING = Pattern.compile("(?:(\\d+\\.\\d+\\.\\d+\\.\\d+):)?(\\d+)(?::(\\d+))?");
    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    private static final Pattern STATUS_RANGE = Pattern.compile("(\\d{3})(-(\\d{3}))?");

    private final Path root;
    private final Function<String, Optional<String>> readFile;
    private final Map<String, String> env;
    private final List<ConfigProblem> problems = new ArrayList<>();
    private final Map<String, Declared> declaredDependencies = new LinkedHashMap<>();
    private final Map<String, String> fileEnv = new LinkedHashMap<>();

    /** A service's depends_on as read, kept to check names once every service is known. */
    private record Declared(Location location, List<Dependency> dependencies) {}

    private ConfigParser(Path root, Map<String, String> baseEnv, Function<String, Optional<String>> readFile) {
        this.root = root;
        this.readFile = readFile;
        this.env = new HashMap<>(baseEnv);
    }

    /**
     * Parses and validates the file.
     *
     * @param root the project folder (where devdesk.yaml lives)
     * @param baseEnv variables available to {@code ${VAR}}, before env_files are added
     * @param readFile reads a file relative to {@code root}; empty if it doesn't exist (core does no file I/O itself)
     */
    public static ProjectSpec parse(
            String yaml, Path root, Map<String, String> baseEnv, Function<String, Optional<String>> readFile)
            throws ConfigException {
        return new ConfigParser(root, baseEnv, readFile).project(YamlReader.read(yaml));
    }

    private ProjectSpec project(YamlNode document) throws ConfigException {
        if (!(document instanceof YamlNode.Mapping top)) {
            throw ConfigException.single(
                    document.location(),
                    "",
                    "expected keys like version: and services:, found " + YamlNode.describe(document));
        }
        checkKeys(top, "", TOP_KEYS);

        Optional<YamlNode> version = get(top, "version");
        if (version.isEmpty()) {
            problem(top.location(), "", "missing \"version: 1\" at the top");
        } else if (!(version.get() instanceof YamlNode.Scalar s && "1".equals(s.text()))) {
            problem(
                    version.get().location(),
                    "version",
                    "only version 1 exists, found " + YamlNode.describe(version.get()));
        }
        String name = get(top, "name")
                .flatMap(n -> text(n, "name"))
                .orElse(
                        root.getFileName() == null
                                ? "project"
                                : root.getFileName().toString());
        get(top, "env_files").ifPresent(this::envFiles);

        Map<String, ServiceSpec> services = new LinkedHashMap<>();
        Optional<YamlNode> servicesNode = get(top, "services");
        if (servicesNode.isEmpty()) {
            problem(top.location(), "", "missing \"services:\": nothing to run");
        } else if (servicesNode.get() instanceof YamlNode.Mapping list
                && !list.entries().isEmpty()) {
            list.entries()
                    .forEach((serviceName, entry) ->
                            service(serviceName, entry).ifPresent(s -> services.put(serviceName, s)));
            checkDependenciesExist(list.entries().keySet(), services);
        } else {
            problem(
                    servicesNode.get().location(),
                    "services",
                    "expected at least one service (name: then its settings)");
        }

        Map<String, Profile> profiles =
                get(top, "profiles").map(p -> profiles(p, services.keySet())).orElse(Map.of());
        Map<String, TaskSpec> tasks =
                get(top, "tasks").map(t -> tasks(t, services.keySet())).orElse(Map.of());

        if (problems.isEmpty()) {
            checkNoCycle(services);
        }
        if (!problems.isEmpty()) {
            throw new ConfigException(problems);
        }
        return new ProjectSpec(name, root, fileEnv, services, profiles, tasks);
    }

    /** {@code tasks: { seed: { command, directory?, environment?, needs?: [db], description?, confirm? } }}. */
    private Map<String, TaskSpec> tasks(YamlNode node, Set<String> serviceNames) {
        Map<String, TaskSpec> out = new LinkedHashMap<>();
        if (!(node instanceof YamlNode.Mapping m)) {
            problem(node.location(), "tasks", "expected task names, like seed: then its command:");
            return out;
        }
        m.entries().forEach((name, entry) -> {
            String path = "tasks." + name;
            if (!SERVICE_NAME.matcher(name).matches()) {
                problem(entry.keyLocation(), path, "task names use lowercase letters, digits, - and _ (e.g. \"seed\")");
            }
            // A task is often only its command: "seed: python3 seed.py" is accepted too.
            if (entry.value() instanceof YamlNode.Scalar) {
                interpolated(entry.value(), path)
                        .ifPresent(c ->
                                out.put(name, new TaskSpec(name, c, root, Map.of(), List.of(), "", Optional.empty())));
                return;
            }
            if (!(entry.value() instanceof YamlNode.Mapping t)) {
                problem(
                        entry.value().location(),
                        path,
                        "expected command: and its settings, found " + YamlNode.describe(entry.value()));
                return;
            }
            checkKeys(t, path, TASK_KEYS);
            Optional<String> command = required(t, "command", path).flatMap(c -> interpolated(c, path + ".command"));
            Path directory = get(t, "directory")
                    .flatMap(d -> text(d, path + ".directory"))
                    .map(d -> root.resolve(d).normalize())
                    .orElse(root);
            Map<String, String> environment = new LinkedHashMap<>();
            get(t, "environment").ifPresent(e -> environment(e, path + ".environment", environment));
            List<String> needs = new ArrayList<>();
            get(t, "needs")
                    .ifPresent(n -> items(n, path + ".needs")
                            .forEach(item -> text(item, path + ".needs").ifPresent(svc -> {
                                if (serviceNames.contains(svc)) {
                                    needs.add(svc);
                                } else {
                                    problem(
                                            item.location(),
                                            path + ".needs",
                                            "no service \"" + svc + "\" in this project"
                                                    + Suggestions.didYouMean(svc, List.copyOf(serviceNames)));
                                }
                            })));
            String description = get(t, "description")
                    .flatMap(d -> text(d, path + ".description"))
                    .orElse("");
            Optional<String> confirm = get(t, "confirm").flatMap(c -> text(c, path + ".confirm"));
            command.ifPresent(
                    c -> out.put(name, new TaskSpec(name, c, directory, environment, needs, description, confirm)));
        });
        return out;
    }

    /**
     * {@code profiles: { demo: { env_files, environment, services: { api: { environment } } } }}. A profile's own env
     * files are read here, like the project's, and may be used in its values as ${VAR}.
     */
    private Map<String, Profile> profiles(YamlNode node, Set<String> serviceNames) {
        Map<String, Profile> out = new LinkedHashMap<>();
        if (!(node instanceof YamlNode.Mapping m)) {
            problem(node.location(), "profiles", "expected profile names, like demo: then its settings");
            return out;
        }
        m.entries().forEach((name, entry) -> {
            String path = "profiles." + name;
            if (!SERVICE_NAME.matcher(name).matches()) {
                problem(
                        entry.keyLocation(),
                        path,
                        "profile names use lowercase letters, digits, - and _ (e.g. \"demo\")");
            }
            if (!(entry.value() instanceof YamlNode.Mapping p)) {
                problem(
                        entry.value().location(),
                        path,
                        "expected settings like environment:, found " + YamlNode.describe(entry.value()));
                return;
            }
            checkKeys(p, path, PROFILE_KEYS);
            Map<String, String> before = new HashMap<>(env);
            Map<String, String> fromFiles = new LinkedHashMap<>();
            get(p, "env_files").ifPresent(files -> {
                for (YamlNode item : items(files, path + ".env_files")) {
                    text(item, path + ".env_files")
                            .ifPresent(file -> readFile.apply(file)
                                    .ifPresentOrElse(
                                            content -> fromFiles.putAll(EnvFile.parse(content)),
                                            () -> problem(
                                                    item.location(),
                                                    path + ".env_files",
                                                    "file \"" + file + "\" not found in " + root)));
                }
            });
            env.putAll(fromFiles); // the profile's own files count for ${VAR} in its values
            Map<String, String> environment = new LinkedHashMap<>(fromFiles);
            get(p, "environment").ifPresent(e -> environment(e, path + ".environment", environment));
            Map<String, Map<String, String>> perService = new LinkedHashMap<>();
            get(p, "services").ifPresent(sv -> {
                if (!(sv instanceof YamlNode.Mapping list)) {
                    problem(sv.location(), path + ".services", "expected service names, like api: then environment:");
                    return;
                }
                list.entries().forEach((service, e) -> {
                    String spath = path + ".services." + service;
                    if (!serviceNames.contains(service)) {
                        problem(
                                e.keyLocation(),
                                spath,
                                "no service \"" + service + "\" in this project"
                                        + Suggestions.didYouMean(service, List.copyOf(serviceNames)));
                        return;
                    }
                    Map<String, String> own = new LinkedHashMap<>();
                    if (e.value() instanceof YamlNode.Mapping sm) {
                        checkKeys(sm, spath, Set.of("environment"));
                        get(sm, "environment").ifPresent(x -> environment(x, spath + ".environment", own));
                    } else {
                        problem(e.value().location(), spath, "expected environment: under the service");
                    }
                    perService.put(service, own);
                });
            });
            env.clear();
            env.putAll(before);
            out.put(name, new Profile(name, environment, perService));
        });
        return out;
    }

    private void envFiles(YamlNode node) {
        for (YamlNode item : items(node, "env_files")) {
            text(item, "env_files")
                    .ifPresent(file -> readFile.apply(file)
                            .ifPresentOrElse(
                                    content -> {
                                        Map<String, String> vars = EnvFile.parse(content);
                                        env.putAll(vars); // for ${VAR} in this file
                                        fileEnv.putAll(vars); // for the services; later files win, like docker compose
                                    },
                                    () -> problem(
                                            item.location(),
                                            "env_files",
                                            "file \"" + file + "\" not found in " + root)));
        }
    }

    private Optional<ServiceSpec> service(String name, YamlNode.Entry entry) {
        String path = "services." + name;
        if (!SERVICE_NAME.matcher(name).matches()) {
            problem(
                    entry.keyLocation(),
                    path,
                    "service names use lowercase letters, digits, - and _ (e.g. \"my-api\")");
        }
        if (!(entry.value() instanceof YamlNode.Mapping s)) {
            problem(
                    entry.value().location(),
                    path,
                    "expected settings like command:, found " + YamlNode.describe(entry.value()));
            return Optional.empty();
        }
        checkKeys(s, path, SERVICE_KEYS);
        boolean hasCommand = get(s, "command").isPresent();
        boolean hasContainer = get(s, "container").isPresent();
        if (hasCommand && hasContainer) {
            problem(
                    entry.keyLocation(),
                    path,
                    "use either command: (runs on this machine) or container: (runs in "
                            + "Docker), not both. To change what the container runs, put command: inside container:");
        } else if (!hasCommand && !hasContainer) {
            problem(
                    entry.keyLocation(), // a block mapping "starts" at its first key; the service name is clearer
                    path,
                    "missing \"command:\", the thing to run (e.g. command: npm run dev), "
                            + "or \"container:\" (e.g. container: { image: postgres:18 })");
        }
        Path directory = get(s, "directory")
                .flatMap(d -> text(d, path + ".directory"))
                .map(d -> root.resolve(d).normalize())
                .orElse(root);
        Map<String, String> environment = new LinkedHashMap<>();
        get(s, "environment").ifPresent(e -> environment(e, path + ".environment", environment));
        Optional<Workload> workload = hasContainer
                ? get(s, "container").flatMap(c -> container(c, path + ".container", environment))
                : get(s, "command")
                        .flatMap(c -> interpolated(c, path + ".command"))
                        .map(Workload.Command::new);
        List<Dependency> dependsOn = get(s, "depends_on")
                .map(d -> dependencies(d, path + ".depends_on"))
                .orElse(List.of());
        get(s, "depends_on").ifPresent(d -> declaredDependencies.put(name, new Declared(d.location(), dependsOn)));
        List<Integer> ports = new ArrayList<>(
                get(s, "ports").map(p -> ports(p, path + ".ports")).orElse(List.of()));
        // A container's published host ports must be free too, so check them before starting.
        workload.filter(w -> w instanceof Workload.Container)
                .map(w -> ((Workload.Container) w).ports())
                .orElse(List.of())
                .forEach(m -> {
                    if (!ports.contains(m.hostPort())) {
                        ports.add(m.hostPort());
                    }
                });
        HealthCheckSpec health = get(s, "health")
                .flatMap(h -> health(h, path + ".health"))
                .orElse(new HealthCheckSpec.ProcessAlive(HealthCheckSpec.Timing.DEFAULT));
        RestartPolicy restart =
                get(s, "restart").flatMap(r -> restart(r, path + ".restart")).orElse(RestartPolicy.NO);
        Duration grace = get(s, "stop_grace_period")
                .flatMap(g -> duration(g, path + ".stop_grace_period"))
                .orElse(Duration.ofSeconds(10));

        if (health instanceof HealthCheckSpec.Docker && !hasContainer) {
            problem(
                    get(s, "health").orElseThrow().location(),
                    path + ".health",
                    "type: docker reads a container's own HEALTHCHECK; this service isn't a container");
        }
        List<Path> watch =
                get(s, "watch").map(w -> watch(w, path + ".watch", directory)).orElse(List.of());
        // Runs in the service's folder like its command: "npm test", "python3 -m pytest -q".
        Optional<String> test = get(s, "test").flatMap(t -> interpolated(t, path + ".test"));
        // per_branch: true keeps this service's data volumes apart per git branch (see BranchStacks).
        boolean perBranch = get(s, "per_branch")
                .flatMap(b -> text(b, path + ".per_branch"))
                .map("true"::equals)
                .orElse(false);
        // sleep_after: 15m stops the service when nobody used it that long; the next connection starts it again.
        Optional<Duration> sleepAfter = get(s, "sleep_after").flatMap(d -> duration(d, path + ".sleep_after"));
        if (sleepAfter.isPresent() && ports.isEmpty()) {
            problem(
                    get(s, "sleep_after").orElseThrow().location(),
                    path + ".sleep_after",
                    "a sleeping service is woken by a connection to its port: give it ports: first");
        }
        return workload.map(w -> new ServiceSpec(
                name,
                w,
                directory,
                environment,
                dependsOn,
                ports,
                health,
                restart,
                grace,
                watch,
                test,
                perBranch,
                sleepAfter));
    }

    /**
     * {@code watch: true} (the service's folder) or {@code watch: [src, ../shared, app.py]} (folders or single files,
     * relative to it). Changes there restart the service, like nodemon, for any language.
     */
    private List<Path> watch(YamlNode node, String path, Path directory) {
        if (node instanceof YamlNode.Scalar s && ("true".equals(s.text()) || "false".equals(s.text()))) {
            return "true".equals(s.text()) ? List.of(directory) : List.of();
        }
        List<Path> folders = new ArrayList<>();
        for (YamlNode item : items(node, path)) {
            text(item, path).ifPresent(t -> {
                Path folder = directory.resolve(t).normalize();
                if (!folder.startsWith(root)) {
                    problem(
                            item.location(),
                            path,
                            "\"" + t + "\" is outside the project folder: only the project's own"
                                    + " files are watched");
                } else {
                    folders.add(folder);
                }
            });
        }
        return folders;
    }

    /** {@code container: { image, ports, volumes, environment, command }}. Its environment joins the service's. */
    private Optional<Workload> container(YamlNode node, String path, Map<String, String> environment) {
        if (!(node instanceof YamlNode.Mapping c)) {
            problem(
                    node.location(),
                    path,
                    "expected settings like image: postgres:18, found " + YamlNode.describe(node));
            return Optional.empty();
        }
        checkKeys(c, path, CONTAINER_KEYS);
        get(c, "environment").ifPresent(e -> environment(e, path + ".environment", environment));
        List<Workload.PortMapping> ports = new ArrayList<>();
        get(c, "ports")
                .ifPresent(p -> items(p, path + ".ports")
                        .forEach(item -> portMapping(item, path + ".ports").ifPresent(ports::add)));
        List<String> volumes = new ArrayList<>();
        get(c, "volumes")
                .ifPresent(v -> items(v, path + ".volumes")
                        .forEach(item ->
                                interpolated(item, path + ".volumes").ifPresent(text -> volumes.add(volume(text)))));
        Optional<String> command = get(c, "command").flatMap(x -> interpolated(x, path + ".command"));
        return required(c, "image", path)
                .flatMap(i -> interpolated(i, path + ".image"))
                .map(image -> new Workload.Container(image, ports, volumes, command));
    }

    /** "5432:5432", "0.0.0.0:5432:5432" or "5432" (same port on both sides). */
    private Optional<Workload.PortMapping> portMapping(YamlNode node, String path) {
        return text(node, path).flatMap(t -> {
            var m = PORT_MAPPING.matcher(t.strip());
            if (!m.matches()) {
                problem(node.location(), path, "expected \"host:container\" like \"5432:5432\", found \"" + t + "\"");
                return Optional.empty();
            }
            int host = Integer.parseInt(m.group(2));
            int inside = m.group(3) == null ? host : Integer.parseInt(m.group(3));
            if (host < 1 || host > 65_535 || inside < 1 || inside > 65_535) {
                problem(node.location(), path, "ports go from 1 to 65535, found \"" + t + "\"");
                return Optional.empty();
            }
            return Optional.of(new Workload.PortMapping(m.group(1) == null ? "127.0.0.1" : m.group(1), host, inside));
        });
    }

    /** Relative bind mounts ("./data:/var/lib/x") are made absolute; Docker would refuse a relative host path. */
    private String volume(String spec) {
        int colon = spec.indexOf(':');
        String source = colon < 0 ? spec : spec.substring(0, colon);
        if (source.startsWith(".") || source.startsWith("/")) {
            return root.resolve(source).normalize() + (colon < 0 ? "" : spec.substring(colon));
        }
        return spec; // a named volume, managed by Docker
    }

    private void environment(YamlNode node, String path, Map<String, String> into) {
        if (!(node instanceof YamlNode.Mapping m)) {
            problem(node.location(), path, "expected KEY: value pairs, found " + YamlNode.describe(node));
            return;
        }
        m.entries()
                .forEach((key, entry) ->
                        interpolated(entry.value(), path + "." + key).ifPresent(v -> into.put(key, v)));
    }

    /** Accepts both {@code depends_on: [db]} and {@code depends_on: {db: {condition: healthy}}}. */
    private List<Dependency> dependencies(YamlNode node, String path) {
        List<Dependency> result = new ArrayList<>();
        if (node instanceof YamlNode.Mapping m) {
            m.entries().forEach((dep, entry) -> {
                Dependency.Condition condition = Dependency.Condition.STARTED;
                if (entry.value() instanceof YamlNode.Mapping options) {
                    checkKeys(options, path + "." + dep, Set.of("condition"));
                    condition = get(options, "condition")
                            .flatMap(c -> condition(c, path + "." + dep + ".condition"))
                            .orElse(condition);
                }
                result.add(new Dependency(dep, condition));
            });
        } else {
            items(node, path)
                    .forEach(item -> text(item, path)
                            .ifPresent(dep -> result.add(new Dependency(dep, Dependency.Condition.STARTED))));
        }
        return result;
    }

    private Optional<Dependency.Condition> condition(YamlNode node, String path) {
        return text(node, path).flatMap(c -> switch (c) {
            case "started" -> Optional.of(Dependency.Condition.STARTED);
            case "healthy" -> Optional.of(Dependency.Condition.HEALTHY);
            case "completed_successfully" -> Optional.of(Dependency.Condition.COMPLETED_SUCCESSFULLY);
            default -> {
                problem(
                        node.location(),
                        path,
                        "unknown condition \"" + c + "\". Use started, healthy or completed_successfully"
                                + Suggestions.didYouMean(c, List.of("started", "healthy", "completed_successfully")));
                yield Optional.empty();
            }
        });
    }

    private List<Integer> ports(YamlNode node, String path) {
        List<Integer> ports = new ArrayList<>();
        for (YamlNode item : items(node, path)) {
            integer(item, path, 1, 65_535).ifPresent(ports::add);
        }
        return ports;
    }

    private Optional<HealthCheckSpec> health(YamlNode node, String path) {
        if (!(node instanceof YamlNode.Mapping h)) {
            problem(node.location(), path, "expected settings like type: http, found " + YamlNode.describe(node));
            return Optional.empty();
        }
        checkKeys(h, path, HEALTH_KEYS);
        HealthCheckSpec.Timing d = HealthCheckSpec.Timing.DEFAULT;
        var timing = new HealthCheckSpec.Timing(
                get(h, "interval").flatMap(v -> duration(v, path + ".interval")).orElse(d.interval()),
                get(h, "timeout").flatMap(v -> duration(v, path + ".timeout")).orElse(d.timeout()),
                get(h, "retries")
                        .flatMap(v -> integer(v, path + ".retries", 1, 100))
                        .orElse(d.retries()),
                get(h, "start_period")
                        .flatMap(v -> duration(v, path + ".start_period"))
                        .orElse(d.startPeriod()));
        String type = get(h, "type").flatMap(t -> text(t, path + ".type")).orElse("");
        return switch (type) {
            case "process" -> Optional.of(new HealthCheckSpec.ProcessAlive(timing));
            case "docker" -> Optional.of(new HealthCheckSpec.Docker(timing));
            case "tcp" ->
                required(h, "port", path)
                        .flatMap(p -> integer(p, path + ".port", 1, 65_535))
                        .map(port -> new HealthCheckSpec.Tcp(
                                get(h, "host")
                                        .flatMap(v -> text(v, path + ".host"))
                                        .orElse("127.0.0.1"),
                                port,
                                timing));
            case "http" ->
                required(h, "url", path).flatMap(u -> url(u, path + ".url")).map(url -> {
                    int[] range = get(h, "expect_status")
                            .map(r -> statusRange(r, path + ".expect_status"))
                            .orElse(new int[] {200, 399});
                    return new HealthCheckSpec.Http(url, range[0], range[1], timing);
                });
            case "command" ->
                required(h, "command", path)
                        .flatMap(c -> interpolated(c, path + ".command"))
                        .map(c -> new HealthCheckSpec.Command(c, timing));
            default -> {
                problem(
                        h.location(),
                        path + ".type",
                        (type.isEmpty() ? "missing type" : "unknown type \"" + type + "\"")
                                + ". Use process, tcp, http, command or docker"
                                + Suggestions.didYouMean(type, List.of("process", "tcp", "http", "command", "docker")));
                yield Optional.empty();
            }
        };
    }

    private Optional<URI> url(YamlNode node, String path) {
        return interpolated(node, path).flatMap(u -> {
            try {
                URI uri = new URI(u);
                if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null) {
                    return Optional.of(uri);
                }
            } catch (URISyntaxException e) {
                // reported below
            }
            problem(node.location(), path, "\"" + u + "\" is not an http(s) URL like http://127.0.0.1:3000/health");
            return Optional.empty();
        });
    }

    private int[] statusRange(YamlNode node, String path) {
        var m = text(node, path).map(STATUS_RANGE::matcher).filter(java.util.regex.Matcher::matches);
        if (m.isEmpty()) {
            problem(node.location(), path, "expected a status like 200 or a range like 200-399");
            return new int[] {200, 399};
        }
        int min = Integer.parseInt(m.get().group(1));
        return new int[] {
            min, m.get().group(3) == null ? min : Integer.parseInt(m.get().group(3))
        };
    }

    private Optional<RestartPolicy> restart(YamlNode node, String path) {
        return text(node, path).flatMap(r -> switch (r) {
            // YAML 1.1 tools turn a bare `no` into false; accept both.
            case "no", "false" -> Optional.of(RestartPolicy.NO);
            case "on-failure" -> Optional.of(RestartPolicy.ON_FAILURE);
            case "always" -> Optional.of(RestartPolicy.ALWAYS);
            default -> {
                problem(
                        node.location(),
                        path,
                        "unknown restart policy \"" + r + "\". Use no, on-failure or always"
                                + Suggestions.didYouMean(r, List.of("no", "on-failure", "always")));
                yield Optional.empty();
            }
        });
    }

    /** Runs after all services were read, because a dependency may be declared further down the file. */
    private void checkDependenciesExist(Set<String> serviceNames, Map<String, ServiceSpec> services) {
        declaredDependencies.forEach((name, declared) -> {
            String path = "services." + name + ".depends_on";
            for (Dependency d : declared.dependencies()) {
                if (d.service().equals(name)) {
                    problem(declared.location(), path, "\"" + name + "\" can't depend on itself");
                } else if (!serviceNames.contains(d.service())) {
                    problem(
                            declared.location(),
                            path,
                            "unknown service \"" + d.service() + "\""
                                    + Suggestions.didYouMean(d.service(), serviceNames));
                } else if (d.condition() == Dependency.Condition.HEALTHY
                        && services.containsKey(d.service())
                        && services.get(d.service()).health() instanceof HealthCheckSpec.ProcessAlive) {
                    // Otherwise "name" would wait forever for a health verdict that never comes.
                    problem(
                            declared.location(),
                            path,
                            "\"" + name + "\" waits for \"" + d.service()
                                    + "\" to be healthy, but \"" + d.service()
                                    + "\" has no health check. Add one to it "
                                    + "(e.g. health: { type: tcp, port: 5432 }) or use condition: started");
                }
            }
        });
    }

    private void checkNoCycle(Map<String, ServiceSpec> services) {
        try {
            DependencyGraph.of(services.values());
        } catch (DependencyCycleException e) {
            String first = e.cycle().getFirst();
            problem(declaredDependencies.get(first).location(), "services." + first + ".depends_on", e.getMessage());
        }
    }

    // ---- small typed readers: each reports a problem and returns empty on bad input ----

    private void checkKeys(YamlNode.Mapping m, String path, Set<String> allowed) {
        m.entries().forEach((key, entry) -> {
            if (!allowed.contains(key)) {
                problem(
                        entry.keyLocation(),
                        path,
                        "unknown key \"" + key + "\"" + Suggestions.didYouMean(key, allowed));
            }
        });
    }

    private static Optional<YamlNode> get(YamlNode.Mapping m, String key) {
        return Optional.ofNullable(m.entries().get(key)).map(YamlNode.Entry::value);
    }

    private Optional<YamlNode> required(YamlNode.Mapping m, String key, String path) {
        Optional<YamlNode> value = get(m, key);
        if (value.isEmpty()) {
            problem(m.location(), path, "missing \"" + key + ":\"");
        }
        return value;
    }

    private Optional<String> text(YamlNode node, String path) {
        if (node instanceof YamlNode.Scalar s && !s.isNull()) {
            return Optional.of(s.text());
        }
        problem(node.location(), path, "expected a value, found " + YamlNode.describe(node));
        return Optional.empty();
    }

    private Optional<String> interpolated(YamlNode node, String path) {
        return text(node, path).flatMap(t -> {
            StringBuilder missing = new StringBuilder();
            Optional<String> value = Interpolation.resolve(t, env, missing);
            if (value.isEmpty()) {
                problem(
                        node.location(),
                        path,
                        "variable ${" + missing + "} is not set. Set it, add it to an env_files "
                                + "file, or give a default: ${" + missing + ":-value}");
            }
            return value;
        });
    }

    private Optional<Integer> integer(YamlNode node, String path, int min, int max) {
        return text(node, path).flatMap(t -> {
            try {
                int n = Integer.parseInt(t);
                if (n >= min && n <= max) {
                    return Optional.of(n);
                }
            } catch (NumberFormatException e) {
                // reported below
            }
            problem(
                    node.location(),
                    path,
                    "expected a whole number from " + min + " to " + max + ", found \"" + t + "\"");
            return Optional.empty();
        });
    }

    private Optional<Duration> duration(YamlNode node, String path) {
        return text(node, path).flatMap(t -> {
            Optional<Duration> d = Durations.parse(t);
            if (d.isEmpty()) {
                problem(node.location(), path, "expected a duration like 500ms, 10s or 2m, found \"" + t + "\"");
            }
            return d;
        });
    }

    private List<YamlNode> items(YamlNode node, String path) {
        if (node instanceof YamlNode.Sequence s) {
            return s.items();
        }
        problem(node.location(), path, "expected a list like [a, b], found " + YamlNode.describe(node));
        return List.of();
    }

    private void problem(Location location, String path, String message) {
        problems.add(new ConfigProblem(location, path, message));
    }
}
