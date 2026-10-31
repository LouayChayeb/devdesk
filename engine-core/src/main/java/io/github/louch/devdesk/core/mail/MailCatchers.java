package io.github.louch.devdesk.core.mail;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the project's Mailpit with no configuration: a container of the {@code axllent/mailpit} image with its web port
 * published, or a native {@code mailpit} command.
 */
public final class MailCatchers {

    /** Mailpit's web UI and API port, inside its container and by default when run natively. */
    static final int WEB_PORT = 8025;
    /** Mailpit's SMTP port, same rule. */
    static final int SMTP_PORT = 1025;

    private static final Pattern LISTEN = Pattern.compile("(?:--listen|-l)[ =](?:[^\\s:]*:)?(\\d+)");
    private static final Pattern SMTP = Pattern.compile("(?:--smtp|-s)[ =](?:[^\\s:]*:)?(\\d+)");

    private MailCatchers() {}

    /** Every Mailpit the project runs, in file order; empty when it has none. */
    public static List<MailCatcher> find(ProjectSpec project) {
        List<MailCatcher> found = new ArrayList<>();
        for (ServiceSpec s : project.services().values()) {
            catcher(s).ifPresent(found::add);
        }
        return found;
    }

    /** The Mailpit behind {@code service}, or empty when that service isn't one (or doesn't exist). */
    public static Optional<MailCatcher> find(ProjectSpec project, String service) {
        return Optional.ofNullable(project.services().get(service)).flatMap(MailCatchers::catcher);
    }

    private static Optional<MailCatcher> catcher(ServiceSpec s) {
        return switch (s.workload()) {
            case Workload.Container c
            when isMailpitImage(c.image()) -> {
                int smtp = c.ports().stream()
                        .filter(p -> p.containerPort() == SMTP_PORT)
                        .mapToInt(Workload.PortMapping::hostPort)
                        .findFirst()
                        .orElse(-1);
                yield c.ports().stream()
                        .filter(p -> p.containerPort() == WEB_PORT)
                        .findFirst()
                        .map(p -> new MailCatcher(
                                s.name(), "http://" + host(p.hostAddress()) + ":" + p.hostPort(), smtp));
            }
            case Workload.Command c
            when isMailpitCommand(c.commandLine()) -> {
                // Flags win, then Mailpit's own environment variables (MP_UI_BIND_ADDR="0.0.0.0:8025"), then defaults.
                int web = port(LISTEN, c.commandLine())
                        .or(() -> envPort(s, "MP_UI_BIND_ADDR"))
                        .orElse(WEB_PORT);
                int smtp = port(SMTP, c.commandLine())
                        .or(() -> envPort(s, "MP_SMTP_BIND_ADDR"))
                        .orElse(SMTP_PORT);
                yield Optional.of(new MailCatcher(s.name(), "http://127.0.0.1:" + web, smtp));
            }
            default -> Optional.empty();
        };
    }

    /** "axllent/mailpit", "axllent/mailpit:v1.21", "docker.io/axllent/mailpit@sha256:…". */
    public static boolean isMailpitImage(String image) {
        String name = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        int slash = name.lastIndexOf('/');
        int colon = name.lastIndexOf(':');
        return name.substring(slash + 1, colon > slash ? colon : name.length()).equals("mailpit");
    }

    /** "mailpit", "./bin/mailpit --listen :9025", "/usr/local/bin/mailpit -s 127.0.0.1:2525". */
    static boolean isMailpitCommand(String commandLine) {
        String first = commandLine.strip().split("\\s+")[0];
        return first.equals("mailpit") || first.endsWith("/mailpit");
    }

    private static Optional<Integer> port(Pattern flag, String commandLine) {
        Matcher m = flag.matcher(commandLine);
        return m.find() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }

    private static Optional<Integer> envPort(ServiceSpec s, String name) {
        String v = s.environment().getOrDefault(name, "");
        int colon = v.lastIndexOf(':');
        String digits = colon < 0 ? v : v.substring(colon + 1);
        return digits.matches("\\d{1,5}") ? Optional.of(Integer.parseInt(digits)) : Optional.empty();
    }

    /** A port published on every address is reached on 127.0.0.1. */
    private static String host(String published) {
        return published.equals("0.0.0.0") ? "127.0.0.1" : published;
    }
}
