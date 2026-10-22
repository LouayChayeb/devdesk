package io.github.louch.devdesk.core.mail;

import io.github.louch.devdesk.core.http.RequestTrouble;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The links in a mail, and which of the project's services each one goes to: "confirm your account" in a sign-up mail
 * points at the api, so DevDesk can open it as a request instead of in a browser.
 */
public final class MailLinks {

    /** Plain-text URLs end at a space, a quote or an angle bracket; trailing punctuation is the sentence's. */
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+");
    /** Links in HTML are in href="…", with entities like &amp; still in them. */
    private static final Pattern HREF =
            Pattern.compile("href\\s*=\\s*[\"'](https?://[^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    /** At most this many links per mail (a newsletter can have hundreds). */
    static final int MAX = 30;

    private MailLinks() {}

    /** @param service the project's service at that address, when it's one on this machine */
    public record Link(String url, Optional<String> service) {}

    /** Links from the HTML first (that's what the reader clicks), then any extra from the text body, once each. */
    public static List<Link> of(String text, String html, ProjectSpec project) {
        Set<String> urls = new LinkedHashSet<>();
        Matcher h = HREF.matcher(html);
        while (h.find()) {
            urls.add(h.group(1).replace("&amp;", "&"));
        }
        Matcher t = URL.matcher(text);
        while (t.find()) {
            urls.add(t.group().replaceAll("[.,;:!?)\\]]+$", ""));
        }
        List<Link> out = new ArrayList<>();
        for (String u : urls) {
            if (out.size() == MAX) {
                break;
            }
            out.add(new Link(u, RequestTrouble.serviceAt(u, project)));
        }
        return out;
    }
}
