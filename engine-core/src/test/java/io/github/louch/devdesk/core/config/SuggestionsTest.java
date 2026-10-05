package io.github.louch.devdesk.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SuggestionsTest {

    @Test
    void distanceCountsSingleLetterEdits() {
        assertThat(Suggestions.distance("postgress", "postgres")).isEqualTo(1);
        assertThat(Suggestions.distance("comand", "command")).isEqualTo(1);
        assertThat(Suggestions.distance("kitten", "sitting")).isEqualTo(3);
    }

    @Test
    void unrelatedWordsGetNoSuggestion() {
        assertThat(Suggestions.closest("frontend", List.of("db", "api"))).isEmpty();
    }
}
