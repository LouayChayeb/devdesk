package io.github.louch.devdesk.core.bundle;

import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Takes secrets out of text before it leaves the machine: values of secret-looking settings in
 * devdesk.yaml, passwords inside connection URLs, and any secret value wherever it appears in a log line.
 */
public final class Redact {

    static final String HIDDEN = "***";

    /**
     * Every "key: value" or "KEY=value" pair, also several on one line ({@code { POSTGRES_PASSWORD: dev, PORT: 1 }}):
     * the key, the separator, and the value (quoted, or up to a comma, brace or space).
     */
    private static final Pattern PAIR = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_.-]*)([\"']?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^,{}\\[\\s][^,}]*?)(?=\\s*[,}]|\\s*$)");
    /** "postgres://user:password@host" → the password part. */
    private static final Pattern URL_PASSWORD = Pattern.compile("(://[^:/@\\s]+:)([^@\\s]+)(@)");
    /** Secrets shorter than this are left alone in free text: "1" or "dev" would hide half of every log. */
    static final int MIN_SECRET = 4;

    private Redact() {}

    /** Each line whose key looks secret keeps its key and loses its value; passwords in URLs go too. */
    public static String settings(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            Matcher m = PAIR.matcher(line);
            StringBuilder l = new StringBuilder();
            while (m.find()) {
                String replacement =
                        ServiceEnvironment.looksSecret(m.group(1)) ? m.group(1) + m.group(2) + HIDDEN : m.group();
                m.appendReplacement(l, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(l);
            out.append(URL_PASSWORD.matcher(l).replaceAll("$1" + HIDDEN + "$3")).append('\n');
        }
        return out.substring(0, Math.max(0, out.length() - 1));
    }

    /** Every occurrence of every given secret value replaced, longest first (one secret can contain another). */
    public static String values(String text, Collection<String> secrets) {
        List<String> sorted = secrets.stream()
                .filter(s -> s != null && s.length() >= MIN_SECRET)
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
        String out = text;
        for (String s : sorted) {
            out = out.replace(s, HIDDEN);
        }
        return URL_PASSWORD.matcher(out).replaceAll("$1" + HIDDEN + "$3");
    }
}
