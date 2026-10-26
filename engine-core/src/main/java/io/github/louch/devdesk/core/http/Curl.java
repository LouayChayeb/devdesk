package io.github.louch.devdesk.core.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Requests as curl commands: "Copy as curl" for a terminal or a bug report, and pasting one in to import it. */
public final class Curl {

    /**
     * What -X accepts: real methods are letters (GET, PROPFIND). Nothing the shell would read differently, because
     * "copy as curl" writes the method unquoted.
     */
    private static final Pattern METHOD = Pattern.compile("[A-Za-z0-9._-]+");

    private Curl() {}

    /** One line that runs the same request in a terminal. Values are quoted for sh with single quotes. */
    public static String of(HttpCall call) {
        StringBuilder b = new StringBuilder("curl");
        if (!call.method().equals("GET") || call.body() instanceof HttpCall.Body.Form) {
            b.append(" -X ").append(call.method());
        }
        // A URL starting with "-" would read back as an option; --url says what it is.
        b.append(call.url().startsWith("-") ? " --url " : " ").append(quote(call.url()));
        for (HttpCall.Header h : call.sentHeaders()) {
            if (call.body() instanceof HttpCall.Body.Form && h.name().equalsIgnoreCase("Content-Type")) {
                continue; // --data-urlencode sets it
            }
            b.append(" -H ").append(quote(h.name() + ": " + h.value()));
        }
        switch (call.body()) {
            case HttpCall.Body.None n -> {
                // nothing to send
            }
            case HttpCall.Body.Text t -> b.append(" --data-raw ").append(quote(t.text()));
            case HttpCall.Body.GraphQl g ->
                b.append(" --data-raw ").append(quote(GraphQl.payload(g.query(), g.variables())));
            case HttpCall.Body.Form f ->
                f.fields().forEach(x -> b.append(" --data-urlencode ").append(quote(x.name() + "=" + x.value())));
            case HttpCall.Body.Multipart m ->
                m.fields()
                        .forEach(x -> b.append(" -F ")
                                .append(quote(x.name() + "=" + (x.file() ? "@" + x.value() : x.value()))));
        }
        return b.toString();
    }

    /** 'it'\''s': the sh way to put a single quote inside single quotes. */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Reads a curl command (as copied from browser dev tools or docs): the URL, -X, -H, -d/--data*, -F, -u, -b.
     *
     * @throws IllegalArgumentException with what's wrong, when it isn't a curl command with a URL
     */
    public static HttpCall parse(String command) {
        List<String> words = words(command);
        if (words.isEmpty() || !words.getFirst().equals("curl")) {
            throw new IllegalArgumentException("that isn't a curl command (it should start with curl)");
        }
        String method = null;
        String url = null;
        List<HttpCall.Header> headers = new ArrayList<>();
        List<String> data = new ArrayList<>();
        List<HttpCall.Field> form = new ArrayList<>();
        List<HttpCall.Field> encoded = new ArrayList<>();
        Optional<HttpCall.Auth> auth = Optional.empty();
        boolean get = false;
        for (int i = 1; i < words.size(); i++) {
            String w = words.get(i);
            String next = i + 1 < words.size() ? words.get(i + 1) : "";
            switch (w) {
                case "-X", "--request" -> {
                    if (!METHOD.matcher(next).matches()) {
                        throw new IllegalArgumentException("\"" + next + "\" after " + w + " isn't an HTTP method");
                    }
                    method = next.toUpperCase(Locale.ROOT);
                    i++;
                }
                case "-H", "--header" -> {
                    int colon = next.indexOf(':');
                    if (colon > 0) {
                        headers.add(new HttpCall.Header(
                                next.substring(0, colon).strip(),
                                next.substring(colon + 1).strip()));
                    }
                    i++;
                }
                case "-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--json" -> {
                    data.add(next);
                    if (w.equals("--json")) {
                        headers.add(new HttpCall.Header("Content-Type", "application/json"));
                    }
                    i++;
                }
                case "--data-urlencode" -> {
                    int eq = next.indexOf('=');
                    encoded.add(
                            eq > 0
                                    ? new HttpCall.Field(next.substring(0, eq), next.substring(eq + 1), false)
                                    : new HttpCall.Field(next, "", false));
                    i++;
                }
                case "-F", "--form" -> {
                    int eq = next.indexOf('=');
                    String name = eq > 0 ? next.substring(0, eq) : next;
                    String value = eq > 0 ? next.substring(eq + 1) : "";
                    boolean file = value.startsWith("@");
                    form.add(new HttpCall.Field(name, file ? value.substring(1) : value, file));
                    i++;
                }
                case "-u", "--user" -> {
                    int colon = next.indexOf(':');
                    auth = Optional.of(
                            colon >= 0
                                    ? new HttpCall.Auth.Basic(next.substring(0, colon), next.substring(colon + 1))
                                    : new HttpCall.Auth.Basic(next, ""));
                    i++;
                }
                case "-b", "--cookie" -> {
                    headers.add(new HttpCall.Header("Cookie", next));
                    i++;
                }
                case "-A", "--user-agent" -> {
                    headers.add(new HttpCall.Header("User-Agent", next));
                    i++;
                }
                case "-e", "--referer" -> {
                    headers.add(new HttpCall.Header("Referer", next));
                    i++;
                }
                case "--url" -> {
                    url = next;
                    i++;
                }
                case "-G", "--get" -> get = true;
                default -> {
                    if (!w.startsWith("-") && url == null) {
                        url = w;
                    }
                    // other flags (-s, -L, --compressed, -k, -i, -v) don't change the request itself
                }
            }
        }
        if (url == null) {
            throw new IllegalArgumentException("the curl command has no URL");
        }
        HttpCall.Body body;
        if (!form.isEmpty()) {
            body = new HttpCall.Body.Multipart(form);
        } else if (!encoded.isEmpty()) {
            body = new HttpCall.Body.Form(encoded);
        } else if (!data.isEmpty() && !get) {
            body = new HttpCall.Body.Text(String.join("&", data));
        } else {
            body = new HttpCall.Body.None();
        }
        if (get && !data.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + String.join("&", data);
        }
        if (method == null) {
            method = body instanceof HttpCall.Body.None ? "GET" : "POST";
        }
        return new HttpCall(method, url, headers, body, auth.orElse(new HttpCall.Auth.None()));
    }

    /** Splits like sh does: single quotes literal, double quotes with \" escapes, backslash escapes outside quotes. */
    static List<String> words(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    w.append(c);
                }
            } else if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else if (c == '\\' && line.startsWith("\n", i + 1)) {
                    i++; // a continued line inside double quotes: sh drops both
                } else if (c == '\\' && i + 1 < line.length() && "\"\\$`".indexOf(line.charAt(i + 1)) >= 0) {
                    w.append(line.charAt(++i));
                } else {
                    w.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '$' && i + 1 < line.length() && line.charAt(i + 1) == '\'') {
                quote = '\''; // bash's $'...': close enough for copied commands
                inWord = true;
                i++;
            } else if (c == '\\' && line.startsWith("\n", i + 1)) {
                i++; // backslash + line break outside quotes continues the command on the next line, as in sh
            } else if (c == '\\' && line.startsWith("\r\n", i + 1)) {
                i += 2; // the same, copied from Windows
            } else if (c == '\\' && i + 1 < line.length()) {
                w.append(line.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    out.add(w.toString());
                    w.setLength(0);
                    inWord = false;
                }
            } else {
                w.append(c);
                inWord = true;
            }
        }
        if (inWord) {
            out.add(w.toString());
        }
        return out;
    }

    /** For a form body sent by the engine: {@code name=Ada&city=S%C3%A3o+Paulo}. */
    public static String formEncoded(List<HttpCall.Field> fields) {
        List<String> parts = new ArrayList<>();
        for (HttpCall.Field f : fields) {
            parts.add(URLEncoder.encode(f.name(), StandardCharsets.UTF_8) + "="
                    + URLEncoder.encode(f.value(), StandardCharsets.UTF_8));
        }
        return String.join("&", parts);
    }
}
