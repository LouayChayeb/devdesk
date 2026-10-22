package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.RequestBook.Book;
import io.github.louch.devdesk.core.http.RequestBook.SavedRequest;
import io.github.louch.devdesk.core.http.RequestBook.Sent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonRequestBookTest {

    @TempDir
    Path dir;

    @Test
    void whatIsStoredIsLoadedBackAndOnlyTheOwnerCanReadIt() throws IOException {
        var file = dir.resolve("requests").resolve("shop-1234.json");
        var call = new HttpCall(
                "POST",
                "{{api.url}}/orders",
                List.of(new HttpCall.Header("Authorization", "Bearer {{token}}")),
                Optional.of("{\"total\": 3}"));
        var book = Book.empty()
                .withSaved(new SavedRequest("r1", "create order", "orders", call))
                .withVariables(Map.of("token", "t0k"))
                .withSent(
                        new Sent(Instant.parse("2026-10-07T10:00:00Z"), call, Optional.empty(), Duration.ofMillis(12)));

        new JsonRequestBook(file).store(book);

        assertThat(new JsonRequestBook(file).load()).isEqualTo(book);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
    }

    @Test
    void aMissingOrDamagedFileStartsEmpty() throws IOException {
        assertThat(new JsonRequestBook(dir.resolve("none.json")).load()).isEqualTo(Book.empty());
        Files.writeString(dir.resolve("bad.json"), "{ not json");
        assertThat(new JsonRequestBook(dir.resolve("bad.json")).load()).isEqualTo(Book.empty());
    }
}
