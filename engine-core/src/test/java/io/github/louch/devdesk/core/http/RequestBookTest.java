package io.github.louch.devdesk.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.http.RequestBook.Book;
import io.github.louch.devdesk.core.http.RequestBook.SavedRequest;
import io.github.louch.devdesk.core.http.RequestBook.Sent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RequestBookTest {

    private static final HttpCall GET = new HttpCall("GET", "{{api.url}}/health", List.of(), Optional.empty());

    @Test
    void savingTheSameIdReplacesInPlace() {
        var book = Book.empty()
                .withSaved(new SavedRequest("a", "health", "", GET))
                .withSaved(new SavedRequest("b", "orders", "shop", GET))
                .withSaved(new SavedRequest("a", "health check", "", GET));

        assertThat(book.saved()).extracting(SavedRequest::name).containsExactly("health check", "orders");
        assertThat(book.without("a").saved()).extracting(SavedRequest::id).containsExactly("b");
        assertThat(book.find("b")).isPresent();
    }

    @Test
    void historyIsNewestFirstAndCapped() {
        var book = Book.empty();
        for (int i = 0; i < RequestBook.HISTORY_KEPT + 5; i++) {
            book = book.withSent(new Sent(Instant.ofEpochSecond(i), GET, Optional.of(200), Duration.ofMillis(1)));
        }

        assertThat(book.history()).hasSize(RequestBook.HISTORY_KEPT);
        assertThat(book.history().getFirst().at()).isEqualTo(Instant.ofEpochSecond(RequestBook.HISTORY_KEPT + 4));
    }
}
