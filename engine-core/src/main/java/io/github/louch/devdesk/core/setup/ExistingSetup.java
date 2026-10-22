package io.github.louch.devdesk.core.setup;

import static io.github.louch.devdesk.core.setup.ComposeImport.entry;
import static io.github.louch.devdesk.core.setup.ComposeImport.list;
import static io.github.louch.devdesk.core.setup.ComposeImport.mapping;
import static io.github.louch.devdesk.core.setup.ComposeImport.text;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.config.YamlNode;
import io.github.louch.devdesk.core.config.YamlReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an existing devdesk.yaml back into wizard cards, for "Edit setup". Works on the raw text, not the parsed
 * project: {@code ${DB_PASSWORD}} must stay {@code ${DB_PASSWORD}}, or saving would copy the secret into the file.
 */
public final class ExistingSetup {

    private static final Pattern ORIGIN = Pattern.compile("(?m)^  # from (.+)\\n  ([a-z0-9][a-z0-9_-]*):");

    private ExistingSetup() {}

    /** The draft. Fails when the file isn't readable YAML; the wizard then starts from a scan instead. */
    public static SetupDraft read(String yaml, String folderName) throws ConfigException {
        YamlNode top = YamlReader.read(yaml);
        String name = entry(top, "name").flatMap(ComposeImport::text).orElse(FolderScan.projectName(folderName));
        List<String> envFiles = entry(top, "env_files")
                .map(e -> list(e).stream().flatMap(i -> text(i).stream()).toList())
                .orElse(List.of());
        // "# from docker-compose.yml" above a service is where the wizard found it: keep it through edits.
        Map<String, String> origins = new LinkedHashMap<>();
        Matcher m = ORIGIN.matcher(yaml);
        while (m.find()) {
            origins.put(m.group(2), m.group(1).strip());
        }
        List<SetupDraft.Service> services = new ArrayList<>();
        entry(top, "services")
                .ifPresent(s -> mapping(s)
                        .forEach((n, node) -> services.add(service(n, node, origins.getOrDefault(n, "devdesk.yaml")))));
        List<String> notes = yaml.lines().anyMatch(l -> l.strip().startsWith("#") && !SetupYaml.isOwnComment(l))
                ? List.of(
                        "Your devdesk.yaml has comments. Saving from the wizard rewrites the file, so they will be lost.")
                : List.of();
        return new SetupDraft(name, envFiles, services, notes, keptSections(yaml));
    }

    /**
     * The top-level sections the cards don't cover ({@code profiles:}, {@code tasks:}), copied line for line: from the section's key to
     * the next top-level key.
     */
    static String keptSections(String yaml) {
        StringBuilder kept = new StringBuilder();
        boolean inside = false;
        for (String line : yaml.lines().toList()) {
            boolean topLevel = !line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#");
            if (topLevel) {
                inside = line.startsWith("profiles:") || line.startsWith("tasks:");
            }
            if (inside) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }

    private static SetupDraft.Service service(String name, YamlNode s, String origin) {
        Optional<YamlNode> container = entry(s, "container");
        Map<String, String> env = new LinkedHashMap<>(map(entry(s, "environment")));
        Map<String, String> extras = new LinkedHashMap<>();
        entry(s, "stop_grace_period").flatMap(ComposeImport::text).ifPresent(g -> extras.put("stop_grace_period", g));
        SetupDraft.Health health =
                entry(s, "health").map(h -> health(h, extras)).orElse(SetupDraft.Health.PROCESS);
        Map<String, String> deps = new LinkedHashMap<>();
        entry(s, "depends_on").ifPresent(d -> {
            if (d instanceof YamlNode.Mapping m) {
                m.entries()
                        .forEach((dep, e) -> deps.put(
                                dep,
                                entry(e.value(), "condition")
                                        .flatMap(ComposeImport::text)
                                        .orElse("started")));
            } else {
                list(d).forEach(i -> text(i).ifPresent(dep -> deps.put(dep, "started")));
            }
        });
        String restart = entry(s, "restart").flatMap(ComposeImport::text).orElse("no");
        if (container.isPresent()) {
            YamlNode c = container.get();
            env.putAll(map(entry(c, "environment")));
            return new SetupDraft.Service(
                    name,
                    "container",
                    entry(c, "command").flatMap(ComposeImport::text).orElse(""),
                    "",
                    entry(c, "image").flatMap(ComposeImport::text).orElse(""),
                    strings(entry(c, "ports")),
                    strings(entry(c, "volumes")),
                    env,
                    deps,
                    health,
                    restart,
                    origin,
                    List.of(),
                    extras);
        }
        return new SetupDraft.Service(
                name,
                "command",
                entry(s, "command").flatMap(ComposeImport::text).orElse(""),
                entry(s, "directory").flatMap(ComposeImport::text).orElse(""),
                "",
                strings(entry(s, "ports")),
                List.of(),
                env,
                deps,
                health,
                restart,
                origin,
                List.of(),
                extras);
    }

    private static SetupDraft.Health health(YamlNode h, Map<String, String> extras) {
        String type = entry(h, "type").flatMap(ComposeImport::text).orElse("process");
        String target = switch (type) {
            case "tcp" -> entry(h, "port").flatMap(ComposeImport::text).orElse("");
            case "http" -> entry(h, "url").flatMap(ComposeImport::text).orElse("");
            case "command" -> entry(h, "command").flatMap(ComposeImport::text).orElse("");
            default -> "";
        };
        for (String key : List.of("interval", "timeout", "retries", "expect_status", "host")) {
            entry(h, key).flatMap(ComposeImport::text).ifPresent(v -> extras.put("health." + key, v));
        }
        return new SetupDraft.Health(
                type,
                target,
                entry(h, "start_period").flatMap(ComposeImport::text).orElse(""));
    }

    private static Map<String, String> map(Optional<YamlNode> node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.ifPresent(n -> mapping(n).forEach((k, v) -> out.put(k, text(v).orElse(""))));
        return out;
    }

    private static List<String> strings(Optional<YamlNode> node) {
        return node.map(n -> list(n).stream().flatMap(i -> text(i).stream()).toList())
                .orElse(List.of());
    }
}
