package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SnapshotTest {

    @Test
    void labelsBecomeSafeFolderNames() {
        assertThat(Snapshot.slug("Before Migration!")).isEqualTo("before-migration");
        assertThat(Snapshot.slug("../../etc/passwd")).isEqualTo("etc-passwd");
        assertThat(Snapshot.slug("!!!")).isEmpty();
        assertThat(Snapshot.slug("a".repeat(30) + " " + "b".repeat(30)))
                .hasSizeLessThanOrEqualTo(40)
                .doesNotEndWith("-");
    }

    @Test
    void aSnapshotWithAnUnsavedPartIsPartial() {
        var saved = new Snapshot.Item(Snapshot.Kind.DATABASE, "db", Optional.of("db.sql"), 10, Optional.empty());
        var skipped = new Snapshot.Item(
                Snapshot.Kind.REDIS, "cache", Optional.empty(), 0, Optional.of("cache isn't running"));
        assertThat(new Snapshot("x", "x", Instant.EPOCH, List.of(saved)).complete())
                .isTrue();
        assertThat(new Snapshot("x", "x", Instant.EPOCH, List.of(saved, skipped)).complete())
                .isFalse();
    }
}
