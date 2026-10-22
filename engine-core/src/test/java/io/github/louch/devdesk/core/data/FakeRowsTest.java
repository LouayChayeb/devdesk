package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

class FakeRowsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-13T10:00:00Z"), ZoneOffset.UTC);

    private static SqlBrowser.Column col(String name, String type, boolean nullable, String def, boolean pk) {
        return new SqlBrowser.Column(name, type, nullable, Optional.ofNullable(def), pk);
    }

    private static FakeRows fake(
            DatabaseTarget.Kind kind,
            List<SqlBrowser.Column> columns,
            List<SqlBrowser.Index> indexes,
            List<SqlBrowser.ForeignKey> links,
            Map<String, List<String>> references,
            Map<String, Long> maxIds) {
        return new FakeRows(
                new FakeRows.Input(new SqlBrowser.Structure(columns, indexes, links), kind, references, maxIds),
                new Random(42),
                CLOCK);
    }

    @Test
    void namesDecideFirstThenTypes() {
        var f = fake(
                DatabaseTarget.Kind.POSTGRES,
                List.of(
                        col("id", "integer", false, "nextval('users_id_seq'::regclass)", true),
                        col("email", "character varying(40)", false, null, false),
                        col("first_name", "text", false, null, false),
                        col("price", "numeric(10,2)", false, null, false),
                        col("description", "text", true, null, false),
                        col("created_at", "timestamp with time zone", false, "now()", false),
                        col("born", "date", false, null, false),
                        col("active", "boolean", false, null, false),
                        col("settings", "jsonb", false, null, false)),
                List.of(),
                List.of(),
                Map.of(),
                Map.of());
        assertThat(f.plan())
                .extracting(FakeRows.Fill::column, FakeRows.Fill::how)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(
                                "id", "left to the database (nextval('users_id_seq'::regclass))"),
                        org.assertj.core.groups.Tuple.tuple("email", "an email (@example.com)"),
                        org.assertj.core.groups.Tuple.tuple("first_name", "a first name"),
                        org.assertj.core.groups.Tuple.tuple("description", "a sentence"),
                        org.assertj.core.groups.Tuple.tuple("born", "random date"));
        var rows = f.rows(20);
        assertThat(rows).hasSize(20);
        for (var r : rows) {
            assertThat(r).doesNotContainKeys("id", "created_at"); // the database fills these
            assertThat(r.get("email").orElseThrow()).endsWith("@example.com").hasSizeLessThanOrEqualTo(40);
            assertThat(r.get("price").orElseThrow()).matches("\\d+\\.\\d{2}");
            assertThat(r.get("born").orElseThrow()).matches("\\d{4}-\\d{2}-\\d{2}");
            assertThat(r.get("active").orElseThrow()).isIn("true", "false");
        }
        // The same seed makes the same rows: tests and bug reports can be repeated.
        assertThat(fake(
                                DatabaseTarget.Kind.POSTGRES,
                                List.of(col("email", "text", false, null, false)),
                                List.of(),
                                List.of(),
                                Map.of(),
                                Map.of())
                        .rows(3))
                .isEqualTo(fake(
                                DatabaseTarget.Kind.POSTGRES,
                                List.of(col("email", "text", false, null, false)),
                                List.of(),
                                List.of(),
                                Map.of(),
                                Map.of())
                        .rows(3));
    }

    @Test
    void aRowsNameAndEmailBelongToTheSamePerson() {
        var f = fake(
                DatabaseTarget.Kind.POSTGRES,
                List.of(
                        col("first_name", "text", false, null, false),
                        col("last_name", "text", false, null, false),
                        col("email", "text", false, null, false)),
                List.of(),
                List.of(),
                Map.of(),
                Map.of());
        for (var r : f.rows(30)) {
            String expected = java.text.Normalizer.normalize(
                            r.get("first_name").orElseThrow() + "."
                                    + r.get("last_name").orElseThrow(),
                            java.text.Normalizer.Form.NFD)
                    .toLowerCase()
                    .replaceAll("[^a-z.]", "");
            assertThat(r.get("email").orElseThrow()).startsWith(expected).endsWith("@example.com");
        }
    }

    @Test
    void uniqueColumnsNeverRepeatAndPlainIdsFollowTheLargest() {
        var f = fake(
                DatabaseTarget.Kind.MYSQL,
                List.of(col("id", "int", false, null, true), col("status", "varchar(10)", false, null, false)),
                List.of(new SqlBrowser.Index("u_status", List.of("status"), true)),
                List.of(),
                Map.of(),
                Map.of("id", 41L));
        var rows = f.rows(50);
        assertThat(rows.getFirst().get("id")).contains("42");
        assertThat(rows.getLast().get("id")).contains("91");
        var statuses = new HashSet<String>();
        rows.forEach(r -> statuses.add(r.get("status").orElseThrow()));
        assertThat(statuses).hasSize(50); // 3 statuses, made unique with the row number
    }

    @Test
    void linksPointAtExistingRowsOrExplainWhyNot() {
        var link = new SqlBrowser.ForeignKey("fk", List.of("user_id"), "public", "users", List.of("id"));
        var cols = List.of(col("user_id", "integer", false, null, false));
        var ok = fake(
                DatabaseTarget.Kind.POSTGRES,
                cols,
                List.of(),
                List.of(link),
                Map.of("user_id", List.of("7", "9")),
                Map.of());
        assertThat(ok.problem()).isEmpty();
        assertThat(ok.rows(10))
                .allSatisfy(r -> assertThat(r.get("user_id").orElseThrow()).isIn("7", "9"));

        var empty = fake(DatabaseTarget.Kind.POSTGRES, cols, List.of(), List.of(link), Map.of(), Map.of());
        assertThat(empty.problem()).hasValueSatisfying(p -> assertThat(p).contains("users", "Fill that first"));
    }

    @Test
    void sqliteNumbersItsOwnIntegerPrimaryKeyAndMysqlEnumsUseTheirValues() {
        var f = fake(
                DatabaseTarget.Kind.SQLITE,
                List.of(col("id", "integer", false, null, true), col("done", "boolean", false, null, false)),
                List.of(),
                List.of(),
                Map.of(),
                Map.of());
        assertThat(f.rows(1).getFirst()).containsOnlyKeys("done");
        assertThat(f.rows(5))
                .allSatisfy(r -> assertThat(r.get("done").orElseThrow()).isIn("0", "1"));

        var e = fake(
                DatabaseTarget.Kind.MYSQL,
                List.of(col("size", "enum('small','large','it''s big')", false, null, false)),
                List.of(),
                List.of(),
                Map.of(),
                Map.of());
        assertThat(e.rows(30))
                .allSatisfy(r -> assertThat(r.get("size").orElseThrow()).isIn("small", "large", "it's big"));
    }

    @Test
    void anUnknownRequiredTypeIsExplained() {
        var f = fake(
                DatabaseTarget.Kind.POSTGRES,
                List.of(col("shape", "geometry", false, null, false), col("area", "box", true, null, false)),
                List.of(),
                List.of(),
                Map.of(),
                Map.of());
        assertThat(f.problem()).hasValueSatisfying(p -> assertThat(p).contains("shape", "geometry"));
        assertThat(f.plan()).extracting(FakeRows.Fill::how).anyMatch(h -> h.startsWith("empty"));
    }
}
