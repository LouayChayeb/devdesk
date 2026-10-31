package io.github.louch.devdesk.core.setup;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Writes a draft as devdesk.yaml text, laid out like a person would write it (the file goes into git and gets read in
 * reviews). The result is checked with ConfigParser before it is saved, so this class only has to be readable.
 */
public final class SetupYaml {

    /** Text YAML reads back unchanged without quotes. Anything else is double-quoted. */
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./@%+=$~-][A-Za-z0-9_ ./:@%+=,()${}'\"~-]*");
    /** Values YAML would read as a number, boolean or null; quoted so people see they are text. */
    private static final Pattern LOOKS_TYPED = Pattern.compile("(?i)[-+]?[0-9.]+|true|false|yes|no|on|off|null|~");

    /** The first lines of every file the wizard writes. */
    static final String HEADER = """
            # How DevDesk starts this project. Written by DevDesk's setup wizard: edit it freely,
            # DevDesk reads it every time the project opens (and asks you to trust it again when it changes).
            """;

    private SetupYaml() {}

    /** Comments the wizard writes itself (the header, "# from …" above a service): not the user's own. */
    static boolean isOwnComment(String line) {
        String l = line.strip();
        return HEADER.lines().anyMatch(h -> h.equals(l)) || l.startsWith("# from ");
    }

    /** The devdesk.yaml text for {@code draft}, ready to save. */
    public static String write(SetupDraft draft) {
        StringBuilder y = new StringBuilder();
        y.append(HEADER)
                .append("version: 1\n")
                .append("name: ")
                .append(scalar(draft.name()))
                .append('\n');
        if (!draft.envFiles().isEmpty()) {
            y.append("env_files: ").append(flow(draft.envFiles())).append('\n');
        }
        y.append("\nservices:\n");
        for (int i = 0; i < draft.services().size(); i++) {
            if (i > 0) {
                y.append('\n');
            }
            service(y, draft.services().get(i));
        }
        if (!draft.kept().isBlank()) {
            y.append('\n').append(draft.kept().strip()).append('\n');
        }
        return y.toString();
    }

    private static void service(StringBuilder y, SetupDraft.Service s) {
        if (!s.origin().isBlank()
                && !s.origin().equals("devdesk.yaml")
                && !s.origin().equals("added by you")) {
            y.append("  # from ").append(s.origin()).append('\n');
        }
        y.append("  ").append(s.name()).append(":\n");
        if (s.isContainer()) {
            y.append("    container:\n");
            y.append("      image: ").append(scalar(s.image())).append('\n');
            String build = s.extras().get("build");
            if (build != null) {
                String context =
                        build.equals(".") || build.startsWith("/") || build.startsWith("./") ? build : "./" + build;
                String dockerfile = s.extras().get("build.dockerfile");
                y.append("      build: ")
                        .append(
                                dockerfile == null
                                        ? scalar(context)
                                        : "{ context: " + scalar(context) + ", dockerfile: " + scalar(dockerfile)
                                                + " }")
                        .append('\n');
            }
            String entrypoint = s.extras().get("entrypoint");
            if (entrypoint != null && !entrypoint.isBlank()) {
                y.append("      entrypoint: ").append(scalar(entrypoint)).append('\n');
            }
            if (!s.command().isBlank()) {
                y.append("      command: ").append(scalar(s.command())).append('\n');
            }
            if (!s.ports().isEmpty()) {
                y.append("      ports: ")
                        .append(flow(s.ports().stream().map(SetupYaml::quoted).toList()))
                        .append('\n');
            }
            if (!s.volumes().isEmpty()) {
                y.append("      volumes: ")
                        .append(flow(s.volumes().stream().map(SetupYaml::quoted).toList()))
                        .append('\n');
            }
            environment(y, s.environment(), "      ");
        } else {
            y.append("    command: ").append(scalar(s.command())).append('\n');
            if (!s.directory().isBlank()) {
                y.append("    directory: ").append(scalar(s.directory())).append('\n');
            }
            if (!s.ports().isEmpty()) {
                y.append("    ports: ").append(flow(s.ports())).append('\n');
            }
            environment(y, s.environment(), "    ");
        }
        String ownEnvFiles = s.extras().get("env_files");
        if (ownEnvFiles != null && !ownEnvFiles.isBlank()) {
            y.append("    env_files: ")
                    .append(flow(List.of(ownEnvFiles.split(","))))
                    .append('\n');
        }
        if (!s.dependsOn().isEmpty()) {
            y.append("    depends_on:\n");
            s.dependsOn()
                    .forEach((dep, condition) -> y.append("      ")
                            .append(dep)
                            .append(": { condition: ")
                            .append(condition)
                            .append(" }\n"));
        }
        health(y, s);
        if (!s.restart().isBlank() && !s.restart().equals("no")) {
            y.append("    restart: ").append(s.restart()).append('\n');
        }
        String grace = s.extras().get("stop_grace_period");
        if (grace != null) {
            y.append("    stop_grace_period: ").append(scalar(grace)).append('\n');
        }
    }

    private static void environment(StringBuilder y, Map<String, String> env, String indent) {
        if (env.isEmpty()) {
            return;
        }
        y.append(indent).append("environment:\n");
        env.forEach((k, v) -> y.append(indent)
                .append("  ")
                .append(k)
                .append(": ")
                .append(envValue(v))
                .append('\n'));
    }

    private static void health(StringBuilder y, SetupDraft.Service s) {
        SetupDraft.Health h = s.health();
        List<String> timing = s.extras().keySet().stream()
                .filter(k -> k.startsWith("health."))
                .toList();
        if (h.type().equals("process") && h.startPeriod().isBlank() && timing.isEmpty()) {
            return; // the default: "it's running" is healthy
        }
        y.append("    health:\n");
        y.append("      type: ").append(h.type()).append('\n');
        switch (h.type()) {
            case "tcp" -> y.append("      port: ").append(h.target()).append('\n');
            case "http" -> y.append("      url: ").append(scalar(h.target())).append('\n');
            case "command" ->
                y.append("      command: ").append(scalar(h.target())).append('\n');
            default -> {
                // process and docker need nothing more
            }
        }
        if (!h.startPeriod().isBlank()) {
            y.append("      start_period: ").append(scalar(h.startPeriod())).append('\n');
        }
        timing.forEach(k -> y.append("      ")
                .append(k.substring("health.".length()))
                .append(": ")
                .append(scalar(s.extras().get(k)))
                .append('\n'));
    }

    /** Env values that look like numbers or booleans are quoted, like docker compose files usually do. */
    private static String envValue(String v) {
        return LOOKS_TYPED.matcher(v).matches() ? quoted(v) : scalar(v);
    }

    private static String flow(List<String> items) {
        // Inside [ ] a comma or bracket would end the item: anything but simple words gets quotes.
        return "["
                + String.join(
                        ", ",
                        items.stream()
                                .map(i -> i.startsWith("\"") || i.matches("[A-Za-z0-9_./-]+") ? i : quoted(i))
                                .toList())
                + "]";
    }

    static String scalar(String text) {
        boolean plain = !text.isEmpty()
                && PLAIN.matcher(text).matches()
                && !text.endsWith(" ")
                && !text.endsWith(":")
                && !text.contains(": ")
                && !text.contains(" #")
                && !text.startsWith("\"")
                && !text.startsWith("'")
                && !LOOKS_TYPED.matcher(text).matches();
        return plain ? text : quoted(text);
    }

    /** A YAML double-quoted string: only \ and " need escaping (plus control characters, which we don't write). */
    static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
