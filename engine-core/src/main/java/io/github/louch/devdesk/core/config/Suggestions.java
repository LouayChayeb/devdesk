package io.github.louch.devdesk.core.config;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/** "Did you mean ...?" for typos, using edit distance. */
final class Suggestions {

    private Suggestions() {}

    /** The closest candidate, if it's within 2 edits (enough for typos, not for unrelated words). */
    static Optional<String> closest(String typed, Collection<String> candidates) {
        return candidates.stream()
                .filter(c -> distance(typed, c) <= 2)
                .min(Comparator.comparingInt(c -> distance(typed, c)));
    }

    static String didYouMean(String typed, Collection<String> candidates) {
        return closest(typed, candidates)
                .map(c -> ". Did you mean \"" + c + "\"?")
                .orElse("");
    }

    /** Levenshtein distance: how many single-letter inserts, deletes or replacements turn a into b. */
    static int distance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int replace = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(replace, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
