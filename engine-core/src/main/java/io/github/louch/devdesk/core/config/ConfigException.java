package io.github.louch.devdesk.core.config;

import java.util.List;
import java.util.stream.Collectors;

/** devdesk.yaml is invalid. Carries every problem found, not just the first, so users fix them in one go. */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    private final transient List<ConfigProblem> problems;

    public ConfigException(List<ConfigProblem> problems) {
        super(problems.size() + " problem(s) in devdesk.yaml");
        this.problems = List.copyOf(problems);
    }

    static ConfigException single(Location location, String path, String message) {
        return new ConfigException(List.of(new ConfigProblem(location, path, message)));
    }

    /** Every problem found, never empty. */
    public List<ConfigProblem> problems() {
        return problems;
    }

    /** All problems, one per line, sorted by position in the file. */
    public String format(String fileName) {
        return problems.stream()
                .sorted((a, b) -> a.location().line() != b.location().line()
                        ? Integer.compare(a.location().line(), b.location().line())
                        : Integer.compare(a.location().column(), b.location().column()))
                .map(p -> p.format(fileName))
                .collect(Collectors.joining("\n"));
    }
}
