package io.github.louch.devdesk.linux.history;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.history.HistoryEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlHistoryStoreTest {

    @TempDir
    Path dir;

    @Test
    void entriesSurviveAReopenAndAreReadInTimeOrderAcrossDays() {
        var store = new JsonlHistoryStore(dir);
        Instant yesterday = Instant.now().minusSeconds(86_400);
        Instant now = Instant.now();
        store.append(new HistoryEntry(
                now, "api", HistoryEntry.Kind.STATE, "crashed: exited with error code 1", Map.of("to", "crashed")));
        store.append(new HistoryEntry(yesterday, "db", HistoryEntry.Kind.RESOURCES, "", Map.of("memory", 1234)));

        var reopened = new JsonlHistoryStore(dir);
        var all = reopened.read(yesterday.minusSeconds(1));

        assertThat(all).extracting(HistoryEntry::service).containsExactly("db", "api");
        assertThat(all.getLast().summary()).isEqualTo("crashed: exited with error code 1");
        assertThat(all.getFirst().data()).containsEntry("memory", 1234);
        assertThat(reopened.read(now.minusSeconds(60)))
                .extracting(HistoryEntry::service)
                .containsExactly("api");
    }

    @Test
    void halfWrittenLineAfterACrashIsSkipped() throws Exception {
        var store = new JsonlHistoryStore(dir);
        store.append(new HistoryEntry(Instant.now(), "api", HistoryEntry.Kind.WARNING, "ok", Map.of()));
        Path today = dir.resolve(LocalDate.now(ZoneOffset.UTC) + ".jsonl");
        Files.writeString(today, "{\"at\":\"2026-10-04T1", java.nio.file.StandardOpenOption.APPEND);

        assertThat(store.read(Instant.EPOCH)).hasSize(1);
    }

    @Test
    void daysOlderThanTwoWeeksAreDeleted() throws Exception {
        Path old = dir.resolve(LocalDate.now(ZoneOffset.UTC).minusDays(20) + ".jsonl");
        Path recent = dir.resolve(LocalDate.now(ZoneOffset.UTC).minusDays(3) + ".jsonl");
        Files.writeString(old, "");
        Files.writeString(recent, "");

        new JsonlHistoryStore(dir);

        assertThat(old).doesNotExist();
        assertThat(recent).exists();
    }
}
