package io.github.louch.devdesk.core.doctor;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.config.YamlNode;
import io.github.louch.devdesk.core.config.YamlReader;
import io.github.louch.devdesk.core.diagnosis.Finding;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Can this machine run this project?" Adds to the pre-start checks (ports, programs, settings) what a new teammate
 * trips over first: the wrong Node/Java/Python version, Docker, images not downloaded yet, packages not installed and
 * a full disk. Each problem says how to fix it; what's fine is listed too, so the answer is never just silence.
 */
public final class Doctor {

    /**
     * What the machine looks like, gathered by the adapters (core runs nothing itself).
     *
     * @param read reads a small file; empty when it doesn't exist
     * @param exists whether a file exists, however big (node_modules' markers can be megabytes)
     * @param versionOf a tool's version output ("v22.3.0"), empty when it isn't installed
     * @param dockerUnavailable why Docker can't be used, empty when it can
     * @param localImages the images Docker already has ("postgres:18"); empty when Docker can't be asked
     * @param freeDiskBytes free space where the project lives
     */
    public record Facts(
            Function<Path, Optional<String>> read,
            Predicate<Path> exists,
            Function<String, Optional<String>> versionOf,
            Optional<String> dockerUnavailable,
            Optional<Set<String>> localImages,
            long freeDiskBytes) {}

    /** Problems (worst first) and what was checked and is fine. */
    public record Report(List<Finding> problems, List<String> passed) {}

    static final long GIB = 1024L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.shared();
    private static final Map<String, String> NAMES = Map.of(
            "node",
            "Node",
            "python",
            "Python",
            "java",
            "Java",
            "pnpm",
            "pnpm",
            "yarn",
            "Yarn",
            "mvn",
            "Maven",
            "gradle",
            "Gradle",
            "go",
            "Go",
            "dotnet",
            ".NET SDK");

    private Doctor() {}

    /** @param preflight what the diagnosis engine found before starting (taken ports, missing programs, settings) */
    public static Report check(ProjectSpec project, Facts facts, List<Finding> preflight) {
        List<Finding> problems = new ArrayList<>(preflight);
        List<String> passed = new ArrayList<>();
        tools(project, facts, preflight, problems, passed);
        docker(project, facts, problems, passed);
        packages(project, facts, problems);
        disk(facts, problems, passed);
        for (var rule : List.of(
                Map.entry("port-conflict", "The ports the project uses are free"),
                Map.entry("program-missing", "Every program the services start was found"),
                Map.entry("missing-env", "Every setting .env.example asks for is set"))) {
            if (preflight.stream().noneMatch(f -> f.ruleId().equals(rule.getKey()))) {
                passed.add(rule.getValue());
            }
        }
        problems.sort((a, b) -> a.severity().compareTo(b.severity())); // ERROR before WARNING, stable otherwise
        return new Report(problems, passed);
    }

    private static void tools(
            ProjectSpec project, Facts facts, List<Finding> preflight, List<Finding> problems, List<String> passed) {
        Map<String, List<ToolNeeds.Need>> byTool = new LinkedHashMap<>();
        ToolNeeds.of(project, facts.read()).stream()
                .filter(n -> !n.tool().equals("docker"))
                .forEach(n ->
                        byTool.computeIfAbsent(n.tool(), t -> new ArrayList<>()).add(n));
        byTool.forEach((tool, needs) -> {
            String name = NAMES.getOrDefault(tool, tool);
            Optional<String> output = facts.versionOf().apply(tool);
            Optional<List<Integer>> installed = output.flatMap(VersionSpec::parse);
            Set<String> users = new LinkedHashSet<>(
                    needs.stream().map(ToolNeeds.Need::service).toList());
            if (output.isEmpty()) {
                // "npm: not found" is already explained by program-missing for the service that runs it.
                boolean explained = preflight.stream()
                        .anyMatch(f -> f.ruleId().equals("program-missing") && users.contains(f.service()));
                if (!explained) {
                    String wanted = needs.stream()
                            .flatMap(n -> n.version().stream())
                            .findFirst()
                            .map(VersionSpec::suggestion)
                            .orElse("");
                    problems.add(new Finding(
                            "tool-missing",
                            Finding.Severity.ERROR,
                            needs.getFirst().service(),
                            name + " isn't installed",
                            String.join(", ", users) + " need" + (users.size() == 1 ? "s " : " ") + name
                                    + ", but it isn't on this machine (or not on the PATH DevDesk uses).",
                            List.of("searched the PATH for " + tool),
                            List.of(Install.hint(tool, wanted))));
                }
                return;
            }
            String version = installed.map(Doctor::join).orElse(output.get().strip());
            List<String> fine = new ArrayList<>();
            boolean wrong = false;
            // One line per requirement file, not per service: eight services sharing one .python-version are one fix.
            Map<String, List<ToolNeeds.Need>> bySource = new LinkedHashMap<>();
            needs.stream()
                    .filter(n -> n.version().isPresent())
                    .forEach(n -> bySource.computeIfAbsent(
                                    n.source() + "\n" + n.version().get(), k -> new ArrayList<>())
                            .add(n));
            for (List<ToolNeeds.Need> group : bySource.values()) {
                if (installed.isEmpty()) {
                    break;
                }
                ToolNeeds.Need n = group.getFirst();
                String spec = n.version().get();
                String who = group.size() > 2
                        ? group.size() + " services"
                        : String.join(
                                " and ",
                                group.stream().map(ToolNeeds.Need::service).toList());
                boolean one = group.size() == 1;
                Optional<Boolean> ok = VersionSpec.satisfies(spec, installed.get());
                if (ok.isEmpty()) {
                    fine.add(who + " ask" + (one ? "s" : "") + " for \"" + spec + "\" (" + n.source()
                            + "), not checked");
                } else if (ok.get()) {
                    fine.add(who + " need" + (one ? "s " : " ") + spec + " (" + n.source() + ")");
                } else {
                    wrong = true;
                    problems.add(new Finding(
                            "tool-version",
                            Finding.Severity.ERROR,
                            one ? n.service() : "", // a shared requirement is about the project, not one service
                            name + " " + version + " found, " + who + " need" + (one ? "s " : " ") + spec,
                            n.source() + " asks for " + name + " " + spec
                                    + (one
                                            ? ""
                                            : " (for "
                                                    + String.join(
                                                            ", ",
                                                            group.stream()
                                                                    .map(ToolNeeds.Need::service)
                                                                    .toList())
                                                    + ")")
                                    + ". With another version it may not build or start, or behave differently than "
                                    + "for your teammates.",
                            List.of(
                                    n.source() + ": " + spec,
                                    tool + " --version: " + output.get().strip()),
                            List.of(Install.hint(tool, VersionSpec.suggestion(spec)))));
                }
            }
            if (wrong) {
                return; // "✓ Node installed" next to "✗ wrong Node" would only confuse
            }
            passed.add(name + " " + version + " installed"
                    + (fine.isEmpty()
                            ? " (used by " + String.join(", ", users) + ")"
                            : ": " + String.join("; ", fine)));
        });
    }

    private static void docker(ProjectSpec project, Facts facts, List<Finding> problems, List<String> passed) {
        List<ServiceSpec> containers = project.services().values().stream()
                .filter(s -> s.workload() instanceof Workload.Container)
                .toList();
        boolean needed = !containers.isEmpty()
                || ToolNeeds.of(project, facts.read()).stream()
                        .anyMatch(n -> n.tool().equals("docker"));
        if (!needed) {
            return;
        }
        if (facts.dockerUnavailable().isPresent()) {
            String first = containers.isEmpty()
                    ? project.services().keySet().iterator().next()
                    : containers.getFirst().name();
            problems.add(new Finding(
                    "docker-unavailable",
                    Finding.Severity.ERROR,
                    first,
                    "Docker is not available",
                    "This project uses Docker, but " + facts.dockerUnavailable().get(),
                    List.of(),
                    List.of("fix Docker first, then check again")));
            return;
        }
        passed.add("Docker is running");
        if (facts.localImages().isEmpty()) {
            return;
        }
        int missing = 0;
        Map<String, String> built = composeBuilds(project, facts);
        for (ServiceSpec s : containers) {
            Workload.Container c = (Workload.Container) s.workload();
            String image = c.image();
            if (c.build().isPresent()) {
                if (!facts.localImages().get().contains(normalize(image))) {
                    // Not a problem: DevDesk builds it from the project's Dockerfile on the first start.
                    passed.add(s.name() + "'s image is built from "
                            + c.build().get().dockerfile() + " when it first starts");
                }
                continue;
            }
            if (!facts.localImages().get().contains(normalize(image)) && built.containsKey(normalize(image))) {
                // Found on real projects: compose builds this image itself, so no registry has it.
                missing++;
                String composeService = built.get(normalize(image));
                problems.add(new Finding(
                        "image-built-by-compose",
                        Finding.Severity.ERROR,
                        s.name(),
                        image + " is built by docker compose, and isn't built yet",
                        "docker compose builds this image from a Dockerfile (service \"" + composeService + "\"),"
                                + " so it can't be downloaded: starting " + s.name() + " would fail with \"pull"
                                + " access denied\". Build it once, then DevDesk uses it.",
                        List.of(
                                "docker image ls: no " + normalize(image),
                                "docker compose: " + composeService + " has build: and image: " + image),
                        List.of("docker compose build " + composeService)));
            } else if (!facts.localImages().get().contains(normalize(image))) {
                missing++;
                problems.add(new Finding(
                        "image-not-pulled",
                        Finding.Severity.WARNING,
                        s.name(),
                        image + " isn't downloaded yet",
                        "The first start of " + s.name() + " downloads it, which can take a few minutes. "
                                + "Download it now so the start is quick.",
                        List.of("docker image ls: no " + normalize(image)),
                        List.of("docker pull " + image)));
            }
        }
        if (missing == 0 && !containers.isEmpty()) {
            passed.add(
                    containers.size() == 1
                            ? "The container image is downloaded"
                            : "All " + containers.size() + " container images are downloaded");
        }
    }

    /** A Node folder whose package.json lists packages, without node_modules: its start command would fail. */
    private static void packages(ProjectSpec project, Facts facts, List<Finding> problems) {
        // One problem per folder: migrate and api in ./api share one npm install.
        Map<Path, List<ServiceSpec>> byFolder = new LinkedHashMap<>();
        for (ServiceSpec s : project.services().values()) {
            if (s.workload() instanceof Workload.Command cmd
                    && Set.of("npm", "npx", "pnpm", "yarn", "node").contains(ToolNeeds.program(cmd.commandLine()))) {
                byFolder.computeIfAbsent(s.directory(), d -> new ArrayList<>()).add(s);
            }
        }
        byFolder.forEach((folder, services) -> {
            Function<String, Optional<String>> here = f -> facts.read().apply(folder.resolve(f));
            boolean hasPackages =
                    here.apply("package.json").map(Doctor::listsPackages).orElse(false);
            boolean installed =
                    Install.MARKERS.stream().anyMatch(m -> facts.exists().test(folder.resolve("node_modules/" + m)));
            if (!hasPackages || installed) {
                return;
            }
            String dir = project.root().relativize(folder).toString();
            List<String> names = services.stream().map(ServiceSpec::name).toList();
            String who = names.size() == 1
                    ? names.getFirst() + "'s packages aren't"
                    : "The packages of " + String.join(" and ", names) + " aren't";
            problems.add(new Finding(
                    "packages-missing",
                    Finding.Severity.ERROR,
                    names.getFirst(),
                    who + " installed",
                    "package.json lists packages, but there's no node_modules folder: \""
                            + ((Workload.Command) services.getFirst().workload()).commandLine()
                            + "\" would fail with \"Cannot find module\".",
                    List.of((dir.isEmpty() ? "" : dir + "/") + "node_modules: missing"),
                    List.of((dir.isEmpty() ? "" : "cd " + dir + " && ") + Install.nodeInstall(here))));
        });
    }

    private static void disk(Facts facts, List<Finding> problems, List<String> passed) {
        String free = String.format("%.1f GB", facts.freeDiskBytes() / (double) GIB);
        if (facts.freeDiskBytes() < GIB) {
            problems.add(new Finding(
                    "disk-full",
                    Finding.Severity.ERROR,
                    "",
                    "Only " + free + " of disk space left",
                    "Databases, images and logs need room: below 1 GB, writes start failing in strange ways.",
                    List.of("free space where the project lives: " + free),
                    List.of(
                            "docker system prune (removes stopped containers and unused images)",
                            "or delete big files you no longer need")));
        } else if (facts.freeDiskBytes() < 5 * GIB) {
            problems.add(new Finding(
                    "disk-low",
                    Finding.Severity.WARNING,
                    "",
                    "Disk space is low: " + free + " left",
                    "Container images and database files can fill the rest quickly.",
                    List.of("free space where the project lives: " + free),
                    List.of("docker system prune (removes stopped containers and unused images)")));
        } else {
            passed.add(free + " of disk space free");
        }
    }

    private static final List<String> COMPOSE_FILES =
            List.of("compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml");

    /** Images the project's compose file builds itself (build: next to image:), normalized → compose service. */
    static Map<String, String> composeBuilds(ProjectSpec project, Facts facts) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : COMPOSE_FILES) {
            Optional<String> text = facts.read().apply(project.root().resolve(name));
            if (text.isEmpty()) {
                continue;
            }
            try {
                if (YamlReader.read(text.get()) instanceof YamlNode.Mapping top
                        && top.entries().get("services") != null
                        && top.entries().get("services").value() instanceof YamlNode.Mapping services) {
                    services.entries().forEach((service, entry) -> {
                        if (entry.value() instanceof YamlNode.Mapping m
                                && m.entries().containsKey("build")
                                && m.entries().get("image") != null
                                && m.entries().get("image").value() instanceof YamlNode.Scalar image) {
                            out.put(normalize(image.text()), service);
                        }
                    });
                }
            } catch (ConfigException e) {
                // an unreadable compose file: the setup wizard says so; the doctor just can't use it
            }
            break; // compose uses the first file it finds
        }
        return out;
    }

    /** "postgres" → "postgres:latest"; "docker.io/library/postgres:18" → "postgres:18" (how docker image ls names them). */
    static String normalize(String image) {
        String n = image.replaceFirst("^docker\\.io/library/", "").replaceFirst("^docker\\.io/", "");
        int slash = n.lastIndexOf('/');
        return n.contains("@") || n.indexOf(':', slash + 1) >= 0 ? n : n + ":latest";
    }

    private static boolean listsPackages(String packageJson) {
        try {
            var json = JSON.readTree(packageJson);
            return json.path("dependencies").size()
                            + json.path("devDependencies").size()
                    > 0;
        } catch (JacksonException e) {
            return false; // a broken package.json: "npm install" would say so itself
        }
    }

    private static String join(List<Integer> v) {
        return String.join(".", v.stream().map(String::valueOf).toList());
    }
}
