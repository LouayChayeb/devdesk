package io.github.louch.devdesk.docker;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Splits a container {@code command:} into arguments the way a shell would, like Docker Compose does:
 * {@code sh -c 'echo a; exit 3'} becomes [sh, -c, echo a; exit 3]. No variables or globs are expanded.
 */
final class ShellWords {

    private ShellWords() {}

    /** The words, or empty if a quote is never closed. */
    static Optional<List<String>> split(String line) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0; // the open quote character, or 0 outside quotes
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0; // inside single quotes nothing is special, except the closing quote
                } else {
                    word.append(c);
                }
            } else if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else if (c == '\\' && i + 1 < line.length() && "\"\\$`".indexOf(line.charAt(i + 1)) >= 0) {
                    word.append(line.charAt(++i));
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (c == '\\' && i + 1 < line.length()) {
                word.append(line.charAt(++i));
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (quote != 0) {
            return Optional.empty();
        }
        if (inWord) {
            words.add(word.toString());
        }
        return Optional.of(words);
    }
}
