package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.RequestBook.Book;
import io.github.louch.devdesk.core.http.RequestBook.SavedRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedRequestBookTest {

    @TempDir
    Path dir;

    private static SavedRequest req(String id, String collection) {
        return new SavedRequest(
                id, "r" + id, collection, new HttpCall("GET", "{{api.url}}/" + id, List.of(), Optional.empty()));
    }

    @Test
    void aSharedCollectionLivesInTheProjectFolderAndTheRestStaysPrivate() throws Exception {
        Path repo = dir.resolve("project/.devdesk/requests");
        var book = new SharedRequestBook(new JsonRequestBook(dir.resolve("state.json")), repo);
        book.store(new Book(
                List.of(req("1", "Shop API / orders"), req("2", "Mine")), Map.of("token", "s3cret"), List.of()));

        book.setShared("Shop API / orders", true);

        Path file = repo.resolve("shop-api-orders.json");
        assertThat(file).exists();
        assertThat(Files.readString(file))
                .contains("\"collection\" : \"Shop API / orders\"")
                .doesNotContain("s3cret");
        assertThat(Files.readString(dir.resolve("state.json")))
                .contains("\"r2\"")
                .doesNotContain("\"r1\"");
        assertThat(book.load().saved()).extracting(SavedRequest::id).containsExactlyInAnyOrder("1", "2");
        assertThat(book.sharedCollections()).containsExactly("Shop API / orders");

        // A teammate's edit to the file shows up; saving keeps it in the file.
        book.store(book.load().withSaved(req("3", "Shop API / orders")));
        assertThat(Files.readString(file)).contains("\"r3\"");

        book.setShared("Shop API / orders", false);
        assertThat(file).doesNotExist();
        assertThat(book.load().saved()).extracting(SavedRequest::id).containsExactlyInAnyOrder("1", "2", "3");
    }
}
