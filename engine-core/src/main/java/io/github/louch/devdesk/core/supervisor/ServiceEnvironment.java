package io.github.louch.devdesk.core.supervisor;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.TaskSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The environment a service starts with, and where each variable came from. The one place that decides precedence,
 * so what the app shows is exactly what the process gets.
 */
public final class ServiceEnvironment {

    /** Where a variable came from. Later sources win: a service's own block beats an env file beats your shell. */
    public enum Source {
        DEVDESK,
        SHELL,
        ENV_FILE,
        SERVICE
    }

    /** One variable. The value stays inside the engine unless the user explicitly reveals it (principle 7). */
    public record Variable(String key, String value, Source source) {

        /** Names that usually hold secrets: shown with a warning, and never logged. */
        public boolean looksSecret() {
            return ServiceEnvironment.looksSecret(key);
        }
    }

    /** Set unless the user overrides them: most tools then print immediately and keep their colours. */
    static final Map<String, String> OUTPUT_FRIENDLY_DEFAULTS = Map.of("PYTHONUNBUFFERED", "1", "FORCE_COLOR", "1");

    private static final Pattern SECRET = Pattern.compile(
            "SECRET|PASSW|TOKEN|KEY|PRIVATE|CREDENTIAL|AUTH|SESSION|COOKIE|DSN|DATABASE_URL", Pattern.CASE_INSENSITIVE);

    private ServiceEnvironment() {}

    /** Names like API_TOKEN or db.password usually hold secrets (also used for the API client's variables). */
    public static boolean looksSecret(String name) {
        return SECRET.matcher(name).find();
    }

    /**
     * @param shell the environment DevDesk itself was started with. Containers never get it: the host's
     *     environment (and its secrets) stays outside them
     */
    public static List<Variable> of(ProjectSpec project, ServiceSpec service, Map<String, String> shell) {
        Map<String, Variable> env = new LinkedHashMap<>();
        if (!(service.workload() instanceof Workload.Container)) {
            put(env, OUTPUT_FRIENDLY_DEFAULTS, Source.DEVDESK);
            put(env, shell, Source.SHELL);
        }
        put(env, project.environment(), Source.ENV_FILE);
        put(env, service.environment(), Source.SERVICE);
        return List.copyOf(env.values());
    }

    /** A task's values: like a command service's (your shell, the env files, then the task's own block). */
    public static Map<String, String> forTask(ProjectSpec project, TaskSpec task, Map<String, String> shell) {
        Map<String, String> values = new LinkedHashMap<>(OUTPUT_FRIENDLY_DEFAULTS);
        values.putAll(shell);
        values.putAll(project.environment());
        values.putAll(task.environment());
        return values;
    }

    /** Just the values, for starting the process. */
    public static Map<String, String> values(ProjectSpec project, ServiceSpec service, Map<String, String> shell) {
        Map<String, String> values = new LinkedHashMap<>();
        of(project, service, shell).forEach(v -> values.put(v.key(), v.value()));
        return values;
    }

    private static void put(Map<String, Variable> env, Map<String, String> from, Source source) {
        from.forEach((k, v) -> env.put(k, new Variable(k, v, source)));
    }
}
