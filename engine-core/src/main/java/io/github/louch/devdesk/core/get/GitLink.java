package io.github.louch.devdesk.core.get;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A project link as people paste it: {@code https://github.com/owner/repo}, the same with {@code .git}, a branch page
 * ({@code .../tree/dev}), or any https git URL. {@code #ref} at the end picks a branch or tag.
 *
 * @param cloneUrl what git clone gets
 * @param name the folder name it lands in ("repo")
 * @param ref the branch or tag, empty for the default branch
 */
public record GitLink(String cloneUrl, String name, Optional<String> ref) {

    /** GitHub, GitLab, Codeberg, Bitbucket page links: "/tree/<ref>" (or "/-/tree/<ref>") after the repository. */
    private static final Pattern TREE = Pattern.compile("^(/.+?)(?:/-)?/tree/([^/]+)/?$");

    private static final Pattern SAFE_REF = Pattern.compile("[A-Za-z0-9._/-]+");

    /** The link, or why it isn't one DevDesk clones. */
    public sealed interface Parsed {
        /** A link DevDesk clones. */
        record Ok(GitLink link) implements Parsed {}

        /** Not one it clones, and what to do instead. */
        record Refused(String why) implements Parsed {}
    }

    /** Reads a pasted link. Never throws: a link DevDesk can't clone comes back as a refusal that says why. */
    public static Parsed parse(String text) {
        String t = text.strip();
        Optional<String> ref = Optional.empty();
        int hash = t.indexOf('#');
        if (hash >= 0) {
            ref = Optional.of(t.substring(hash + 1)).filter(r -> !r.isBlank());
            t = t.substring(0, hash);
        }
        URI uri;
        try {
            uri = URI.create(t);
        } catch (IllegalArgumentException e) {
            return new Parsed.Refused("\"" + text.strip() + "\" isn't a link. Paste the project's address, like"
                    + " https://github.com/owner/project");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        // https only: plain http and git:// aren't encrypted, and ssh needs keys DevDesk shouldn't touch. file:// is
        // a repository on this machine (a mirror, or a test).
        if (!scheme.equals("https") && !scheme.equals("file")) {
            return new Parsed.Refused(
                    scheme.isEmpty()
                            ? "\"" + text.strip() + "\" isn't a link. Paste the project's address, like"
                                    + " https://github.com/owner/project, or open a folder instead"
                            : "DevDesk only clones https links (" + scheme
                                    + ":// isn't one). Use the https address shown"
                                    + " on the project's page, or clone it yourself and open the folder");
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        Matcher tree = TREE.matcher(path);
        if (tree.matches() && ref.isEmpty()) {
            path = tree.group(1);
            ref = Optional.of(tree.group(2));
        }
        path = path.replaceFirst("/+$", "");
        String last = path.substring(path.lastIndexOf('/') + 1).replaceFirst("\\.git$", "");
        if (last.isBlank() || (scheme.equals("https") && path.split("/").length < 3)) {
            return new Parsed.Refused("\"" + text.strip() + "\" doesn't point at a project. It should look like"
                    + " https://github.com/owner/project");
        }
        if (ref.isPresent()
                && (!SAFE_REF.matcher(ref.get()).matches() || ref.get().startsWith("-"))) {
            return new Parsed.Refused("\"" + ref.get() + "\" isn't a branch or tag name");
        }
        String host = uri.getHost() == null ? "" : uri.getHost();
        String url = scheme.equals("file")
                ? "file://" + path
                : "https://" + host + path + (path.endsWith(".git") ? "" : ".git");
        if (scheme.equals("https") && host.equalsIgnoreCase("github.com") && path.split("/").length > 3) {
            url = "https://github.com" + String.join("/", java.util.Arrays.copyOf(path.split("/"), 3)) + ".git";
            last = path.split("/")[2].replaceFirst("\\.git$", "");
        }
        String name = last.replaceAll("[^A-Za-z0-9._-]", "-");
        return new Parsed.Ok(new GitLink(url, name, ref));
    }
}
