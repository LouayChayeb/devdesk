package io.github.louch.devdesk.core.http;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{name}}} placeholders in requests. Every service with a port gets two for free, so a request never hard-codes
 * a port: {@code {{api.url}}} (http://localhost:3001) and {@code {{api.port}}} (3001). The user adds their own
 * ({@code {{token}}}), and those win over the free ones.
 */
public final class RequestVariables {

    /** {{name}}, with optional spaces inside the braces. Names: letters, digits, dot, dash, underscore. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private RequestVariables() {}

    /** The free variables, from each service's first port (a declared port, or a container's published one). */
    public static Map<String, String> ofServices(ProjectSpec project) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (ServiceSpec s : project.services().values()) {
            firstPort(s).ifPresent(port -> {
                vars.put(s.name() + ".url", "http://localhost:" + port);
                vars.put(s.name() + ".port", String.valueOf(port));
            });
        }
        return vars;
    }

    static Optional<Integer> firstPort(ServiceSpec s) {
        if (!s.ports().isEmpty()) {
            return Optional.of(s.ports().getFirst());
        }
        if (s.workload() instanceof Workload.Container c && !c.ports().isEmpty()) {
            return Optional.of(c.ports().getFirst().hostPort());
        }
        return Optional.empty();
    }

    /** The request with every known placeholder filled in, and the names nobody defined. */
    public static Resolved resolve(HttpCall call, Map<String, String> variables) {
        Set<String> missing = new LinkedHashSet<>();
        List<HttpCall.Header> headers = new ArrayList<>();
        for (HttpCall.Header h : call.headers()) {
            headers.add(new HttpCall.Header(fill(h.name(), variables, missing), fill(h.value(), variables, missing)));
        }
        HttpCall.Body body = switch (call.body()) {
            case HttpCall.Body.None n -> n;
            case HttpCall.Body.Text t -> new HttpCall.Body.Text(fill(t.text(), variables, missing));
            case HttpCall.Body.Form f -> new HttpCall.Body.Form(fields(f.fields(), variables, missing));
            case HttpCall.Body.Multipart m -> new HttpCall.Body.Multipart(fields(m.fields(), variables, missing));
            case HttpCall.Body.GraphQl g ->
                new HttpCall.Body.GraphQl(fill(g.query(), variables, missing), fill(g.variables(), variables, missing));
        };
        HttpCall.Auth auth = switch (call.auth()) {
            case HttpCall.Auth.None n -> n;
            case HttpCall.Auth.Bearer b -> new HttpCall.Auth.Bearer(fill(b.token(), variables, missing));
            case HttpCall.Auth.Basic b ->
                new HttpCall.Auth.Basic(fill(b.user(), variables, missing), fill(b.password(), variables, missing));
        };
        HttpCall filled = new HttpCall(call.method(), fill(call.url(), variables, missing), headers, body, auth);
        return new Resolved(filled, missing);
    }

    private static List<HttpCall.Field> fields(
            List<HttpCall.Field> fields, Map<String, String> variables, Set<String> missing) {
        List<HttpCall.Field> out = new ArrayList<>();
        for (HttpCall.Field f : fields) {
            out.add(new HttpCall.Field(
                    fill(f.name(), variables, missing), fill(f.value(), variables, missing), f.file()));
        }
        return out;
    }

    private static String fill(String text, Map<String, String> variables, Set<String> missing) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = variables.get(m.group(1));
            if (value == null) {
                missing.add(m.group(1));
                value = m.group(); // left as typed, so the user sees which one
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** @param missing placeholders with no value; the request isn't sent while there are any */
    public record Resolved(HttpCall call, Set<String> missing) {
        public Resolved {
            missing = Set.copyOf(missing);
        }
    }
}
