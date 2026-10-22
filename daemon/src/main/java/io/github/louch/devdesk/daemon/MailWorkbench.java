package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.mail.MailBox;
import io.github.louch.devdesk.core.mail.MailCatcher;
import io.github.louch.devdesk.core.mail.MailCatchers;
import io.github.louch.devdesk.core.mail.MailLinks;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.state.ServiceState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * The Mail view: the e-mails the project's app sent to its Mailpit. Only a Mailpit of the open project is ever
 * reached, found from devdesk.yaml like the databases are.
 */
final class MailWorkbench {

    /** Enough for an inbox page; Mailpit keeps more. */
    static final int MAX_LIST = 200;

    private final EngineSession session;
    private final MailBox box;

    MailWorkbench(EngineSession session, MailBox box) {
        this.session = session;
        this.box = box;
    }

    /** The project's mail catchers and whether each one runs. */
    EngineSession.Answer catchers() {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return noProject();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (MailCatcher c : MailCatchers.find(current.get().project())) {
            ServiceState state = current.get().states().getOrDefault(c.service(), new ServiceState.Stopped());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service", c.service());
            m.put("url", c.webUrl());
            m.put("smtpPort", c.smtpPort());
            m.put("state", ApiJson.state(state));
            list.add(m);
        }
        return new EngineSession.Answer(200, Map.of("catchers", list));
    }

    EngineSession.Answer list(String service, String search, int limit) {
        return withCatcher(service, (c, p) -> {
            MailBox.Page page = box.list(c, search == null ? "" : search, Math.clamp(limit, 1, MAX_LIST));
            return Map.of(
                    "total", page.total(),
                    "unread", page.unread(),
                    "mails", page.mails().stream().map(MailWorkbench::summary).toList());
        });
    }

    /** One mail, plus its links and which of the project's services each one goes to. */
    EngineSession.Answer read(String service, String id) {
        return withCatcher(service, (c, p) -> {
            MailBox.Mail m = box.read(c, id);
            Map<String, Object> out = summary(m.summary());
            out.put("cc", m.cc().stream().map(MailBox.Address::toString).toList());
            out.put("text", m.text());
            out.put("html", m.html());
            out.put(
                    "headers",
                    m.headers().stream()
                            .map(h -> Map.of("name", h.name(), "value", h.value()))
                            .toList());
            out.put(
                    "attachments",
                    m.attachments().stream()
                            .map(a -> Map.of(
                                    "partId", a.partId(),
                                    "name", a.name(),
                                    "contentType", a.contentType(),
                                    "size", a.size()))
                            .toList());
            out.put(
                    "links",
                    MailLinks.of(m.text(), m.html(), p).stream()
                            .map(l -> {
                                Map<String, Object> link = new LinkedHashMap<>();
                                link.put("url", l.url());
                                link.put("service", l.service().orElse(null));
                                // As the API client writes it, so the request follows the service if its port changes.
                                link.put(
                                        "request",
                                        l.service()
                                                .map(svc -> "{{" + svc + ".url}}" + pathOf(l.url()))
                                                .orElse(l.url()));
                                return link;
                            })
                            .toList());
            return out;
        });
    }

    /** {ids: [...]} deletes those mails; {ids: []} or no ids empties the inbox. */
    EngineSession.Answer delete(String service, Map<?, ?> body) {
        List<String> ids = new ArrayList<>();
        if (body.get("ids") instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof String s) || !s.matches("[A-Za-z0-9_-]{1,64}")) {
                    return new EngineSession.Answer(400, ApiJson.error("ids are Mailpit message ids"));
                }
                ids.add(s);
            }
        }
        return withCatcher(service, (c, p) -> {
            box.delete(c, ids);
            return Map.of("deleted", ids.isEmpty() ? "all" : ids.size());
        });
    }

    /** An attachment's bytes, for the browser fallback (the page downloads them itself). */
    Optional<byte[]> attachment(String service, String id, String partId) {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return Optional.empty();
        }
        return MailCatchers.find(current.get().project(), service).map(c -> box.attachment(c, id, partId));
    }

    /** {path}: the app's save dialog picked where the attachment goes; the engine writes it there. */
    EngineSession.Answer saveAttachment(String service, String id, String partId, Map<?, ?> body) {
        if (!(body.get("path") instanceof String path) || path.isBlank()) {
            return new EngineSession.Answer(400, ApiJson.error("send {\"path\": \"/where/to/save/it\"}"));
        }
        return withCatcher(service, (c, p) -> {
            byte[] bytes = box.attachment(c, id, partId);
            try {
                Files.write(Path.of(path), bytes);
            } catch (IOException e) {
                throw new DataException("couldn't write " + path + ": " + e.getMessage(), e);
            }
            return Map.of("path", path, "size", bytes.length);
        });
    }

    private EngineSession.Answer withCatcher(String service, BiFunction<MailCatcher, ProjectSpec, Object> call) {
        Optional<EngineSession.Current> current = session.current();
        if (current.isEmpty()) {
            return noProject();
        }
        ProjectSpec project = current.get().project();
        Optional<MailCatcher> catcher = MailCatchers.find(project, service);
        if (catcher.isEmpty()) {
            return new EngineSession.Answer(
                    404,
                    ApiJson.error(service + " isn't a mail catcher DevDesk recognises (a Mailpit container with its"
                            + " web port 8025 published, or a mailpit command)"));
        }
        try {
            return new EngineSession.Answer(200, call.apply(catcher.get(), project));
        } catch (DataException e) {
            return new EngineSession.Answer(422, ApiJson.error(e.getMessage()));
        }
    }

    /** "http://127.0.0.1:18080/orders/1?x=2#top" → "/orders/1?x=2#top". */
    static String pathOf(String url) {
        int start = url.indexOf("//");
        int slash = url.indexOf('/', start < 0 ? 0 : start + 2);
        return slash < 0 ? "" : url.substring(slash);
    }

    private static Map<String, Object> summary(MailBox.Summary s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.id());
        m.put("from", s.from().toString());
        m.put("to", s.to().stream().map(MailBox.Address::toString).toList());
        m.put("subject", s.subject());
        m.put("at", s.at().toString());
        m.put("size", s.size());
        m.put("read", s.read());
        m.put("attachments", s.attachments());
        m.put("snippet", s.snippet());
        return m;
    }

    private static EngineSession.Answer noProject() {
        return new EngineSession.Answer(404, ApiJson.error("no project is open"));
    }
}
