package io.github.louch.devdesk.core.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds secrets typed straight into a request (a token in a header, a password in Basic auth) before the request is
 * written to a file teammates get through git. A {@code {{variable}}} is fine: its value stays on this machine.
 */
public final class SecretScan {

    private static final Set<String> SECRET_HEADERS =
            Set.of("authorization", "cookie", "x-api-key", "api-key", "x-auth-token", "proxy-authorization");
    private static final Pattern ONLY_VARIABLES = Pattern.compile("^(?:[^{}]*\\{\\{[^}]+\\}\\}[^{}]*)+$|^$");
    /** Query parameters whose value is a credential: ?api_key=..., ?token=... */
    private static final Pattern SECRET_QUERY = Pattern.compile(
            "[?&](?:api[_-]?key|token|access_token|secret|password)=([^&{]+)(?:&|$)", Pattern.CASE_INSENSITIVE);

    /** A JSON body with a password or token typed in: {"password": "hunter2"}. */
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "\"(?:password|passwd|secret|token|api_?key|access_token|client_secret)\"\\s*:\\s*\"(?!\\{\\{)[^\"]+\"",
            Pattern.CASE_INSENSITIVE);

    private SecretScan() {}

    /** "Log in: header Authorization", one line per secret found; empty when the request is safe to share. */
    public static List<String> of(String name, HttpCall c) {
        List<String> out = new ArrayList<>();
        for (HttpCall.Header h : c.headers()) {
            if (SECRET_HEADERS.contains(h.name().toLowerCase(Locale.ROOT)) && !usesVariable(h.value())) {
                out.add(name + ": header " + h.name());
            }
        }
        switch (c.auth()) {
            case HttpCall.Auth.Bearer b -> {
                if (!usesVariable(b.token())) {
                    out.add(name + ": Bearer token");
                }
            }
            case HttpCall.Auth.Basic b -> {
                if (!usesVariable(b.password())) {
                    out.add(name + ": Basic password");
                }
            }
            case HttpCall.Auth.None n -> {
                // nothing typed
            }
        }
        if (c.text().map(t -> SECRET_FIELD.matcher(t).find()).orElse(false)) {
            out.add(name + ": a password or token in the body");
        }
        if (SECRET_QUERY.matcher(c.url()).find()) {
            out.add(name + ": a key or token in the URL");
        }
        return out;
    }

    /** "Bearer {{token}}" or "" is fine; "Bearer eyJhbGci..." isn't. */
    static boolean usesVariable(String value) {
        String stripped = value.replaceFirst("(?i)^(bearer|basic|token)\\s+", "");
        return ONLY_VARIABLES.matcher(stripped.strip()).matches();
    }
}
