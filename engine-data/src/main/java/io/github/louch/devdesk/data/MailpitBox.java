package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.mail.MailBox;
import io.github.louch.devdesk.core.mail.MailCatcher;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@link MailBox} over Mailpit's web API (/api/v1), the same one its own web UI uses. */
public final class MailpitBox implements MailBox {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** Attachments bigger than this aren't read into the engine's memory. */
    static final long MAX_ATTACHMENT = 25L * 1024 * 1024;

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    @Override
    public Page list(MailCatcher catcher, String search, int limit) {
        // /search takes Mailpit's own search words ("to:ada is:unread"); /messages lists everything.
        String path = search.isBlank()
                ? "/api/v1/messages?limit=" + limit
                : "/api/v1/search?limit=" + limit + "&query="
                        + URLEncoder.encode(search.strip(), StandardCharsets.UTF_8);
        JsonNode root = json(get(catcher, path));
        List<Summary> mails = new ArrayList<>();
        for (JsonNode m : root.path("messages")) {
            mails.add(summary(m, m.path("Created")));
        }
        int total = root.path(search.isBlank() ? "total" : "messages_count").asInt(mails.size());
        return new Page(total, root.path("unread").asInt(0), mails);
    }

    @Override
    public Mail read(MailCatcher catcher, String id) {
        String safe = URLEncoder.encode(id, StandardCharsets.UTF_8);
        JsonNode m = json(get(catcher, "/api/v1/message/" + safe)); // Mailpit marks it read, as its own UI does
        JsonNode h = json(get(catcher, "/api/v1/message/" + safe + "/headers"));
        List<Header> headers = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : h.properties()) {
            for (JsonNode v : e.getValue()) {
                headers.add(new Header(e.getKey(), v.asString("")));
            }
        }
        List<Attachment> files = new ArrayList<>();
        for (String kind : List.of("Attachments", "Inline")) {
            for (JsonNode a : m.path(kind)) {
                files.add(new Attachment(
                        a.path("PartID").asString(""),
                        a.path("FileName").asString(""),
                        a.path("ContentType").asString(""),
                        a.path("Size").asLong(0)));
            }
        }
        Summary s = summary(m, m.path("Date"));
        Summary withCount =
                new Summary(s.id(), s.from(), s.to(), s.subject(), s.at(), s.size(), true, files.size(), s.snippet());
        return new Mail(
                withCount,
                addresses(m.path("Cc")),
                m.path("Text").asString(""),
                m.path("HTML").asString(""),
                headers,
                files);
    }

    @Override
    public byte[] attachment(MailCatcher catcher, String id, String partId) {
        HttpResponse<byte[]> res = send(
                catcher,
                HttpRequest.newBuilder(uri(
                                catcher,
                                "/api/v1/message/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/part/"
                                        + URLEncoder.encode(partId, StandardCharsets.UTF_8)))
                        .timeout(TIMEOUT)
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (res.body().length > MAX_ATTACHMENT) {
            throw new DataException("that attachment is bigger than 25 MB: open it in Mailpit's own page");
        }
        return res.body();
    }

    @Override
    public void delete(MailCatcher catcher, List<String> ids) {
        // An empty body deletes every message; {"IDs": [...]} only those.
        String body = ids.isEmpty() ? "" : JSON.writeValueAsString(Map.of("IDs", ids));
        send(
                catcher,
                HttpRequest.newBuilder(uri(catcher, "/api/v1/messages"))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Summary summary(JsonNode m, JsonNode at) {
        Instant when;
        try {
            when = Instant.parse(at.asString(""));
        } catch (DateTimeParseException e) {
            when = Instant.EPOCH;
        }
        return new Summary(
                m.path("ID").asString(""),
                address(m.path("From")),
                addresses(m.path("To")),
                m.path("Subject").asString(""),
                when,
                m.path("Size").asLong(0),
                m.path("Read").asBoolean(false),
                m.path("Attachments").isArray()
                        ? m.path("Attachments").size()
                        : m.path("Attachments").asInt(0),
                m.path("Snippet").asString(""));
    }

    private static List<Address> addresses(JsonNode list) {
        List<Address> out = new ArrayList<>();
        for (JsonNode a : list) {
            out.add(address(a));
        }
        return out;
    }

    private static Address address(JsonNode a) {
        return new Address(a.path("Name").asString(""), a.path("Address").asString(""));
    }

    private String get(MailCatcher catcher, String path) {
        return send(
                        catcher,
                        HttpRequest.newBuilder(uri(catcher, path))
                                .timeout(TIMEOUT)
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private <T> HttpResponse<T> send(MailCatcher catcher, HttpRequest req, HttpResponse.BodyHandler<T> handler) {
        try {
            HttpResponse<T> res = http.send(req, handler);
            if (res.statusCode() == 404) {
                throw new DataException(
                        "Mailpit at " + catcher.webUrl() + " doesn't know that mail (deleted meanwhile?)");
            }
            if (res.statusCode() >= 400) {
                throw new DataException("Mailpit at " + catcher.webUrl() + " answered " + res.statusCode());
            }
            return res;
        } catch (ConnectException e) {
            throw new DataException("nothing answers at " + catcher.webUrl() + ": is " + catcher.service()
                    + " running? Start it in Services");
        } catch (IOException e) {
            throw new DataException("couldn't read from Mailpit at " + catcher.webUrl() + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataException("interrupted while reading from Mailpit", e);
        }
    }

    private static URI uri(MailCatcher catcher, String path) {
        return URI.create(catcher.webUrl() + path);
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (JacksonException e) {
            throw new DataException("Mailpit's answer isn't the JSON DevDesk expects: " + e.getOriginalMessage(), e);
        }
    }
}
