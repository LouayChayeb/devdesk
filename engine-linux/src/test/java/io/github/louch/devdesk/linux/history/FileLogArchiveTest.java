package io.github.louch.devdesk.linux.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.logs.LogLine;
import io.github.louch.devdesk.core.logs.LogQuery;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileLogArchiveTest {

    @TempDir
    Path dir;

    private static final Instant T = Instant.parse("2026-10-12T23:59:58Z");

    private FileLogArchive archive() {
        var a = new FileLogArchive(dir, java.time.Clock.fixed(T, java.time.ZoneOffset.UTC));
        a.append(new LogLine(T, "api", "stdout", "api: listening on 18080"));
        a.append(new LogLine(T.plusSeconds(1), "api", "stderr", "Traceback (most recent call last):"));
        a.append(new LogLine(T.plusSeconds(1), "web", "stdout", "web: GET /orders 200"));
        a.append(new LogLine(T.plusSeconds(3), "api", "stdout", "api: GET /orders/42 500")); // the next day (UTC)
        return a;
    }

    @Test
    void searchFindsTextAcrossDaysNewestFirstIgnoringCase() throws Exception {
        var a = archive();

        var found =
                a.search(new LogQuery("ORDERS", Set.of(), false, T.minusSeconds(60)).matcher(), T.minusSeconds(60), 10);

        assertThat(found).extracting(LogLine::text).containsExactly("api: GET /orders/42 500", "web: GET /orders 200");
        assertThat(Files.list(dir)).hasSize(2); // one file per day
    }

    @Test
    void servicesErrorsOnlyAndRegularExpressionsNarrowIt() throws Exception {
        var a = archive();
        Instant since = T.minusSeconds(60);

        assertThat(a.search(new LogQuery("", Set.of("api"), true, since).matcher(), since, 10))
                .extracting(LogLine::text)
                .containsExactly("Traceback (most recent call last):");
        assertThat(a.search(new LogQuery("/ (4|5)\\d\\d$/", Set.of(), false, since).matcher(), since, 10))
                .extracting(LogLine::text)
                .containsExactly("api: GET /orders/42 500");
        assertThatThrownBy(() -> new LogQuery("/[oops/", Set.of(), false, since).matcher())
                .isInstanceOf(LogQuery.InvalidQuery.class)
                .hasMessageStartingWith("not a valid regular expression");
    }

    @Test
    void aroundShowsTheServicesLinesNextToAMatch() {
        var a = archive();

        assertThat(a.around("api", T.plusSeconds(1), 1, 1))
                .extracting(LogLine::text)
                .containsExactly("api: listening on 18080", "Traceback (most recent call last):");
    }
}
