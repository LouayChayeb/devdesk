package io.github.louch.devdesk.core.http;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * One HTTP request as the user built it. {@code url}, header values, the body and the auth fields may still hold
 * {@code {{variables}}} until {@link RequestVariables#resolve} fills them in.
 */
public record HttpCall(String method, String url, List<Header> headers, Body body, Auth auth) {

    public HttpCall {
        headers = List.copyOf(headers);
    }

    /** A request with a plain text body (or none) and no auth helper: what most requests are. */
    public HttpCall(String method, String url, List<Header> headers, Optional<String> body) {
        this(method, url, headers, body.<Body>map(Body.Text::new).orElse(new Body.None()), new Auth.None());
    }

    /** One header line. Several with the same name are allowed, as in HTTP. */
    public record Header(String name, String value) {}

    /** What the request carries. */
    public sealed interface Body {
        /** No body (a GET). */
        record None() implements Body {}

        /** Text sent as typed: JSON, XML, anything. The Content-Type header is the user's to set. */
        record Text(String text) implements Body {}

        /** An HTML form: {@code name=Ada&city=Paris}, sent as application/x-www-form-urlencoded. */
        record Form(List<Field> fields) implements Body {
            public Form {
                fields = List.copyOf(fields);
            }
        }

        /** A GraphQL query and its variables (JSON text, may be blank), sent as one JSON body. */
        record GraphQl(String query, String variables) implements Body {}

        /** A form with files (multipart/form-data): each part is a text value or a file read from disk. */
        record Multipart(List<Field> fields) implements Body {
            public Multipart {
                fields = List.copyOf(fields);
            }
        }
    }

    /** @param file the value is a path on this machine; the engine sends the file's content */
    public record Field(String name, String value, boolean file) {}

    /** Who the request says it is: written into the Authorization header when sending. */
    public sealed interface Auth {
        /** No auth helper: the user's own headers decide. */
        record None() implements Auth {}

        /** {@code Authorization: Bearer <token>}, the usual for JWTs and API tokens. */
        record Bearer(String token) implements Auth {}

        /** {@code Authorization: Basic base64(user:password)}. */
        record Basic(String user, String password) implements Auth {}
    }

    /** The text body, if it is one. */
    public Optional<String> text() {
        return body instanceof Body.Text t ? Optional.of(t.text()) : Optional.empty();
    }

    /**
     * The headers as sent: the user's, plus Authorization from the auth helper, plus the form's Content-Type. A header
     * the user typed themselves wins (they may know better).
     */
    public List<Header> sentHeaders() {
        List<Header> out = new ArrayList<>(headers);
        switch (auth) {
            case Auth.Bearer b -> addIfMissing(out, "Authorization", "Bearer " + b.token());
            case Auth.Basic b ->
                addIfMissing(
                        out,
                        "Authorization",
                        "Basic "
                                + Base64.getEncoder()
                                        .encodeToString(
                                                (b.user() + ":" + b.password()).getBytes(StandardCharsets.UTF_8)));
            case Auth.None n -> {
                // nothing to add
            }
        }
        if (body instanceof Body.Form) {
            addIfMissing(out, "Content-Type", "application/x-www-form-urlencoded");
        }
        if (body instanceof Body.GraphQl) {
            addIfMissing(out, "Content-Type", "application/json");
        }
        return out;
    }

    private static void addIfMissing(List<Header> headers, String name, String value) {
        if (headers.stream().noneMatch(h -> h.name().equalsIgnoreCase(name))) {
            headers.add(new Header(name, value));
        }
    }
}
