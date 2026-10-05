package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.state.ServiceState;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The program a command starts with can't be found ("command not found", exit 127), or isn't executable (126). */
public final class ProgramMissingRule implements DiagnosisRule {

    /** Shell built-ins and keywords: they never live on PATH, so they can't be "missing". */
    private static final Set<String> SHELL_WORDS = Set.of(
            "cd", "export", "exec", "if", "for", "while", "case", ".", "source", "true", "false", "echo", "printf",
            "test", "[", "set", "unset", "trap", "wait", "sleep", "{", "(", "!", "eval", "read", "ulimit", "umask");

    @Override
    public String id() {
        return "program-missing";
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        ServiceSpec spec = ctx.project().services().get(service);
        if (!(spec.workload() instanceof Workload.Command command)) {
            return List.of();
        }
        boolean beforeStart = ctx.stateOf(service) instanceof ServiceState.Stopped
                && ctx.historyOf(service).exits().isEmpty();
        boolean exited127 = lastExit(ctx, service)
                .map(e -> e.code() == 127 || e.code() == 126)
                .orElse(false);
        if (!beforeStart && !exited127) {
            return List.of();
        }
        Optional<String> program = firstProgram(command.commandLine());
        if (program.isEmpty()) {
            return List.of();
        }
        String path = effectivePath(ctx, spec);
        String name = program.get();
        if (name.contains("/")) {
            Path file = spec.directory().resolve(name).normalize();
            Introspection.FileStatus status = ctx.system().fileStatus(file);
            if (status == Introspection.FileStatus.MISSING) {
                return List.of(finding(
                        service,
                        "`" + name + "` does not exist",
                        service + " runs `" + name + "`, but there is no file at " + file + ".",
                        List.of("checked: " + file),
                        List.of("check the path in devdesk.yaml (it's relative to " + spec.directory() + ")")));
            }
            if (status == Introspection.FileStatus.NOT_EXECUTABLE) {
                return List.of(finding(
                        service,
                        "`" + name + "` is not executable",
                        "The file exists but may not be run as a program.",
                        List.of(file + " has no execute permission"),
                        List.of("chmod +x " + file)));
            }
            return List.of();
        }
        if (ctx.system().findExecutable(name, path).isPresent()) {
            return List.of(); // it's there; a 127 then came from something the command itself ran
        }
        List<String> folders = Arrays.stream(path.split(":"))
                .filter(f -> !f.isBlank())
                .distinct() // PATH often lists a folder twice; once is enough as evidence
                .limit(8)
                .toList();
        return List.of(finding(
                service,
                "Program `" + name + "` is not installed or not on PATH",
                service + " starts with `" + name + "`, but DevDesk can't find it in any PATH folder. "
                        + "In a terminal it may work because your shell setup (nvm, sdkman, ~/.local/bin) adds folders.",
                List.of("searched PATH: " + String.join(":", folders) + (folders.size() == 8 ? ":..." : "")),
                List.of(
                        "install it, or check the spelling in devdesk.yaml",
                        "if it works in your terminal, run `which " + name + "` there and use that full path")));
    }

    private Finding finding(String service, String title, String why, List<String> evidence, List<String> actions) {
        return new Finding(id(), Finding.Severity.ERROR, service, title, why, evidence, actions);
    }

    private static Optional<ExitStatus> lastExit(DiagnosisContext ctx, String service) {
        List<ExitStatus> exits = ctx.historyOf(service).exits();
        return exits.isEmpty() ? Optional.empty() : Optional.of(exits.getLast());
    }

    private static String effectivePath(DiagnosisContext ctx, ServiceSpec spec) {
        return spec.environment()
                .getOrDefault(
                        "PATH",
                        ctx.project()
                                .environment()
                                .getOrDefault("PATH", ctx.environment().getOrDefault("PATH", "")));
    }

    /** The first real program of a shell command line, skipping {@code VAR=value} prefixes and shell built-ins. */
    static Optional<String> firstProgram(String commandLine) {
        for (String word : commandLine.strip().split("\\s+")) {
            if (word.contains("=") && !word.startsWith("=")) {
                continue; // FOO=bar npm start
            }
            if (SHELL_WORDS.contains(word) || word.contains("$") || word.contains("`") || word.isEmpty()) {
                return Optional.empty(); // too dynamic to check without running a shell
            }
            return Optional.of(word.replaceAll("[;&|]+$", ""));
        }
        return Optional.empty();
    }
}
