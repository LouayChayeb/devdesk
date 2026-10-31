package io.github.louch.devdesk.core.http;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import io.github.louch.devdesk.core.state.ServiceState;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Explains a request that got no answer, using what DevDesk knows about the project: "Nothing listens on port 3001.
 * It's api's port, and api is stopped: start it in Services."
 */
public final class RequestTrouble {

    private static final Set<String> LOCAL = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0.0.0.0");

    private RequestTrouble() {}

    /** One plain-words explanation of why the request got no answer, with what to do, using the project's services. */
    public static String explain(
            HttpSender.Outcome.Failed failed, String url, ProjectSpec project, Map<String, ServiceState> states) {
        Optional<URI> uri = parse(url);
        String host = uri.map(URI::getHost).orElse("");
        int port = uri.map(RequestTrouble::port).orElse(-1);
        return switch (failed.reason()) {
            case REFUSED -> refused(host, port, project, states);
            case TIMEOUT ->
                "No answer from " + host + ":" + port + " in time. "
                        + owner(port, project)
                                .map(s -> s.name() + " may be stuck or busy: look at its logs, or restart it.")
                                .orElse("The server may be stuck, or something drops the packets on the way.");
            case UNKNOWN_HOST ->
                "The host name \"" + host + "\" doesn't resolve to an address. The project's services are reached on"
                        + " localhost, e.g. " + example(project) + ".";
            case CLOSED ->
                owner(port, project)
                        .map(s -> s.name() + " accepted the connection, then closed it without answering: its handler"
                                + " most likely crashed on this request. The error is in " + s.name()
                                + "'s logs (the line marked ↻).")
                        .orElse("The server accepted the connection, then closed it without answering ("
                                + failed.message() + "). It most likely crashed on this request: check its logs.");
            case BAD_REQUEST -> "This request can't be sent: " + failed.message() + ".";
            case OTHER -> "The request failed: " + failed.message() + ".";
        };
    }

    private static String refused(String host, int port, ProjectSpec project, Map<String, ServiceState> states) {
        if (!LOCAL.contains(host)) {
            return "Connection refused by " + host + ":" + port + ": the machine is reachable, but nothing listens on"
                    + " that port.";
        }
        Optional<ServiceSpec> owner = owner(port, project);
        if (owner.isEmpty()) {
            return "Nothing listens on port " + port + ", and no service of this project declares it. Check the port"
                    + " in the URL" + (project.services().isEmpty() ? "." : " (" + ports(project) + ").");
        }
        ServiceSpec s = owner.get();
        ServiceState state = states.getOrDefault(s.name(), new ServiceState.Stopped());
        return switch (state) {
            case ServiceState.Stopped _ ->
                "Nothing listens on port " + port + ". It's " + s.name() + "'s port, and " + s.name()
                        + " is stopped: start it in Services.";
            case ServiceState.Starting _ ->
                s.name() + " is still starting, so port " + port + " isn't open yet. Try again in a moment.";
            case ServiceState.WaitingDeps _ ->
                s.name() + " hasn't started yet: it waits for what it depends on. See Services.";
            case ServiceState.Crashed _ ->
                s.name() + " crashed, so nothing listens on port " + port + ". Its last log lines usually say why.";
            case ServiceState.CrashLoop _ ->
                s.name() + " keeps crashing, so nothing listens on port " + port + ". See Diagnosis.";
            case ServiceState.FailedToStart st -> s.name() + " failed to start: " + st.reason() + ".";
            default ->
                s.name() + " is " + state.label() + ", but nothing listens on port " + port + ". It may listen on"
                        + " another port, or only on IPv6 (::1): compare with the Ports page.";
        };
    }

    /** The project's service a URL on this machine goes to, by its port: "http://localhost:18080/x" → "api". */
    public static Optional<String> serviceAt(String url, ProjectSpec project) {
        return parse(url)
                .filter(u -> LOCAL.contains(String.valueOf(u.getHost())))
                .flatMap(u -> owner(port(u), project))
                .map(ServiceSpec::name);
    }

    /** The service that declares this port, if one does. */
    static Optional<ServiceSpec> owner(int port, ProjectSpec project) {
        return project.services().values().stream()
                .filter(s -> s.ports().contains(port)
                        || (s.workload() instanceof Workload.Container c
                                && c.ports().stream().anyMatch(p -> p.hostPort() == port)))
                .findFirst();
    }

    private static String ports(ProjectSpec project) {
        return project.services().values().stream()
                .flatMap(s -> RequestVariables.firstPort(s).stream().map(p -> s.name() + " " + p))
                .collect(Collectors.joining(", "));
    }

    private static String example(ProjectSpec project) {
        List<String> urls = project.services().values().stream()
                .flatMap(s -> RequestVariables.firstPort(s).stream().map(p -> "http://localhost:" + p))
                .toList();
        return urls.isEmpty() ? "http://localhost:3000" : urls.getFirst();
    }

    private static Optional<URI> parse(String url) {
        try {
            return Optional.of(URI.create(url));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static int port(URI u) {
        if (u.getPort() != -1) {
            return u.getPort();
        }
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }
}
