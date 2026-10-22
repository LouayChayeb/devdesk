package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationsTest {

    @Test
    void flywayFileNames() {
        assertThat(Migrations.flywayFile("V3__add_status.sql"))
                .contains(new Migrations.Migration("3", "add status", List.of()));
        assertThat(Migrations.flywayFile("V1_2__split.sql").orElseThrow().id()).isEqualTo("1.2");
        assertThat(Migrations.flywayFile("R__views.sql")).isEmpty();
        assertThat(Migrations.flywayFile("U3__undo.sql")).isEmpty();
        assertThat(Migrations.flywayFile("notes.sql")).isEmpty();
    }

    @Test
    void flywayPendingInVersionOrderWithFailedAndUnknown() {
        var files = List.of("V1__init.sql", "V2__users.sql", "V10__orders.sql", "V9__status.sql").stream()
                .map(f -> Migrations.flywayFile(f).orElseThrow())
                .toList();
        var s = Migrations.compare(
                Migrations.Tool.FLYWAY, files, new Migrations.Applied(List.of("1", "2", "7"), List.of("7")));
        assertThat(s.pending()).extracting(Migrations.Migration::id).containsExactly("9", "10"); // 10 after 9
        assertThat(s.applied()).isEqualTo(2);
        assertThat(s.failed()).containsExactly("7");
        assertThat(s.unknown()).containsExactly("7"); // ran here, but no V7 file in this branch
        assertThat(s.upToDate()).isFalse();
    }

    @Test
    void prismaFolders() {
        var files = List.of("20241001090000_init", "20241003120000_add_status", "migration_lock.toml").stream()
                .flatMap(f -> Migrations.prismaFolder(f).stream())
                .toList();
        assertThat(files).extracting(Migrations.Migration::name).containsExactly("init", "add status");
        var s = Migrations.compare(
                Migrations.Tool.PRISMA, files, new Migrations.Applied(List.of("20241001090000_init"), List.of()));
        assertThat(s.pending()).extracting(Migrations.Migration::id).containsExactly("20241003120000_add_status");
        assertThat(Migrations.compare(
                                Migrations.Tool.PRISMA,
                                files,
                                new Migrations.Applied(
                                        List.of("20241001090000_init", "20241003120000_add_status"), List.of()))
                        .upToDate())
                .isTrue();
    }

    private static Migrations.Migration alembic(String rev, String down, String message) {
        return Migrations.alembicFile("\"\"\"" + message + "\n\nRevision ID: " + rev + "\n\"\"\"\n"
                        + "from alembic import op\n\nrevision = '" + rev + "'\ndown_revision = " + down + "\n"
                        + "branch_labels = None\n\ndef upgrade():\n    pass\n")
                .orElseThrow();
    }

    @Test
    void alembicFollowsTheChainFromTheCurrentHead() {
        var a = alembic("a1", "None", "create users");
        var b = alembic("b2", "'a1'", "add email");
        var c = alembic("c3", "'b2'", "add orders");
        var d = alembic("d4", "'c3'", "add status");
        assertThat(b.parents()).containsExactly("a1");
        assertThat(b.name()).isEqualTo("add email");
        var s = Migrations.compare(
                Migrations.Tool.ALEMBIC, List.of(d, b, a, c), new Migrations.Applied(List.of("b2"), List.of()));
        assertThat(s.pending()).extracting(Migrations.Migration::id).containsExactly("c3", "d4");
        assertThat(s.applied()).isEqualTo(2);
        assertThat(s.unknown()).isEmpty();
    }

    @Test
    void alembicMergeRevisionsAndAHeadFromAnotherBranch() {
        var a = alembic("a1", "None", "base");
        var x = alembic("x1", "'a1'", "left");
        var y = alembic("y1", "'a1'", "right");
        var m = alembic("m1", "('x1', 'y1')", "merge");
        assertThat(m.parents()).containsExactly("x1", "y1");
        var s = Migrations.compare(
                Migrations.Tool.ALEMBIC, List.of(a, x, y, m), new Migrations.Applied(List.of("x1"), List.of()));
        assertThat(s.pending()).extracting(Migrations.Migration::id).containsExactly("y1", "m1");

        var other = Migrations.compare(
                Migrations.Tool.ALEMBIC, List.of(a, x), new Migrations.Applied(List.of("zz9"), List.of()));
        assertThat(other.unknown()).containsExactly("zz9");
    }

    @Test
    void typedAlembicFiles() {
        var t = Migrations.alembicFile(
                        "\"\"\"add status\"\"\"\nrevision: str = 'c3'\n" + "down_revision: Union[str, None] = 'b2'\n")
                .orElseThrow();
        assertThat(t.id()).isEqualTo("c3");
        assertThat(t.parents()).containsExactly("b2");
        assertThat(t.name()).isEqualTo("add status");
    }
}
