package io.github.louch.devdesk.cli;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Turns the words after {@code devdesk run --} into one command line for {@code /bin/sh -c}. */
final class ShellCommand {

    // Characters the shell never treats specially, so such a word needs no quotes.
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:=@%+,-]+");

    private ShellCommand() {}

    /**
     * One word is used as-is, so {@code devdesk run -- 'npm run build && npm start'} keeps its {@code &&}. Several
     * words are each quoted, so {@code devdesk run -- echo "a  b"} prints exactly {@code a  b}.
     */
    static String join(List<String> words) {
        if (words.size() == 1) {
            return words.getFirst();
        }
        return words.stream().map(ShellCommand::quote).collect(Collectors.joining(" "));
    }

    static String quote(String word) {
        if (PLAIN.matcher(word).matches()) {
            return word;
        }
        // Inside single quotes nothing is special, except ' itself: end the quote, add \', reopen.
        return "'" + word.replace("'", "'\\''") + "'";
    }

    /** A short readable name for the command, e.g. "npm" for "/usr/bin/npm run dev". */
    static String nameOf(String commandLine) {
        String first = commandLine.strip().split("\\s+")[0];
        String name = first.substring(first.lastIndexOf('/') + 1);
        return name.isBlank() ? "command" : name;
    }
}
