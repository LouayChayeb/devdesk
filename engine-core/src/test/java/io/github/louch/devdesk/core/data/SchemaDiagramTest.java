package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.data.SchemaDiagram.TableName;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SchemaDiagramTest {

    private static TableName t(String name) {
        return new TableName("public", name);
    }

    private static SqlBrowser.Structure pointsAt(String... targets) {
        return new SqlBrowser.Structure(
                List.of(),
                List.of(),
                List.of(targets).stream()
                        .map(to -> new SqlBrowser.ForeignKey(
                                "fk_" + to,
                                List.of(to + "_id"),
                                to.contains(".") ? to.split("\\.")[0] : "public",
                                to.contains(".") ? to.split("\\.")[1] : to,
                                List.of("id")))
                        .toList());
    }

    @Test
    void aTableSitsAboveEveryTableItPointsAt() {
        var d = SchemaDiagram.of(Map.of(
                t("users"), pointsAt(),
                t("products"), pointsAt(),
                t("orders"), pointsAt("users"),
                t("order_items"), pointsAt("orders", "products")));

        assertThat(d.levels())
                .containsExactly(List.of(t("order_items")), List.of(t("orders")), List.of(t("products"), t("users")));
        assertThat(d.links()).hasSize(3);
        assertThat(d.outside()).isEmpty();
    }

    @Test
    void loopsAndSelfReferencesStillGiveEveryTableALevel() {
        var d = SchemaDiagram.of(Map.of(
                t("employees"), pointsAt("employees"), // manager_id
                t("a"), pointsAt("b"),
                t("b"), pointsAt("a")));

        assertThat(d.levels().stream().flatMap(List::stream)).containsExactlyInAnyOrder(t("employees"), t("a"), t("b"));
        assertThat(d.links()).hasSize(3);
    }

    @Test
    void aLinkToAnotherSchemaIsMarkedOutside() {
        var d = SchemaDiagram.of(Map.of(t("orders"), pointsAt("auth.users")));

        assertThat(d.outside()).containsExactly(new TableName("auth", "users"));
        assertThat(d.levels()).containsExactly(List.of(t("orders")));
    }
}
