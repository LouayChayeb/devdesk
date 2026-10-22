package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.data.TableFile.Format;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TableFileTest {

    private static String export(Format format, SqlDialect dialect) {
        StringWriter out = new StringWriter();
        var w = TableFile.writer(format, out, dialect, "public", "items");
        w.columns(List.of("id", "name", "ok", "meta"), List.of("int4", "text", "bool", "jsonb"));
        w.row(List.of("1", "pen, blue", "t", "{\"a\": 1}"));
        w.row(Arrays.asList("2", "", "f", null));
        w.row(Arrays.asList("3", null, null, null));
        assertThat(w.finish()).isEqualTo(3);
        return out.toString();
    }

    @Test
    void csvKeepsNullAndEmptyTextApart() {
        assertThat(export(Format.CSV, SqlDialect.POSTGRES))
                .isEqualTo("id,name,ok,meta\r\n1,\"pen, blue\",t,\"{\"\"a\"\": 1}\"\r\n2,\"\",f,\r\n3,,,\r\n");
    }

    @Test
    void jsonKeepsNumbersBooleansAndJsonColumns() {
        assertThat(export(Format.JSON, SqlDialect.POSTGRES)).isEqualTo("""
                        [
                          {"id": 1, "name": "pen, blue", "ok": true, "meta": {"a": 1}},
                          {"id": 2, "name": "", "ok": false, "meta": null},
                          {"id": 3, "name": null, "ok": null, "meta": null}
                        ]
                        """);
    }

    @Test
    void sqlInsertsQuoteNamesAndValuesPerDatabase() {
        assertThat(export(Format.SQL, SqlDialect.POSTGRES).lines().findFirst().orElseThrow())
                .isEqualTo("INSERT INTO \"public\".\"items\" (\"id\", \"name\", \"ok\", \"meta\")"
                        + " VALUES ('1', 'pen, blue', 't', '{\"a\": 1}');");
        assertThat(SqlDialect.MYSQL.literal("it's C:\\temp")).isEqualTo("'it''s C:\\\\temp'");
        assertThat(SqlDialect.MYSQL.table("shop", "or`ders")).isEqualTo("`shop`.`or``ders`");
        assertThat(SqlDialect.SQLITE.table("main", "items")).isEqualTo("\"items\"");
    }

    @Test
    void csvReadsQuotesLineBreaksAndNulls() {
        var rows = TableFile.readCsv("\uFEFFid,name,note\r\n1,\"a, \"\"b\"\"\",\"two\nlines\"\n2,,\"\"\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("id", Optional.of("1"))
                .containsEntry("name", Optional.of("a, \"b\""))
                .containsEntry("note", Optional.of("two\nlines"));
        assertThat(rows.get(1)).containsEntry("name", Optional.empty()).containsEntry("note", Optional.of(""));
    }

    @Test
    void whatWasWrittenReadsBackTheSame() {
        var rows = TableFile.readCsv(export(Format.CSV, SqlDialect.POSTGRES));

        assertThat(rows.get(1)).containsEntry("name", Optional.of("")).containsEntry("meta", Optional.empty());
        assertThat(rows.get(0)).containsEntry("meta", Optional.of("{\"a\": 1}"));
    }

    @Test
    void brokenCsvIsExplained() {
        assertThatThrownBy(() -> TableFile.readCsv("a,b\n1,\"x\n")).hasMessageContaining("never closed");
        assertThatThrownBy(() -> TableFile.readCsv("a\n1,2\n")).hasMessageContaining("line 2");
        assertThatThrownBy(() -> TableFile.readCsv("")).hasMessageContaining("empty");
    }

    @Test
    void jsonReadsBackWhatWasWritten() {
        var rows = TableFile.readJson(export(Format.JSON, SqlDialect.POSTGRES));

        assertThat(rows.get(0))
                .containsEntry("id", Optional.of("1"))
                .containsEntry("ok", Optional.of("true"))
                .containsEntry("meta", Optional.of("{\"a\":1}"));
        assertThat(rows.get(2)).containsEntry("name", Optional.empty());
        assertThatThrownBy(() -> TableFile.readJson("{\"a\": 1}")).hasMessageContaining("array of objects");
        assertThatThrownBy(() -> TableFile.readJson("[1, 2]")).hasMessageContaining("item 1");
        assertThatThrownBy(() -> TableFile.readJson("[{")).hasMessageContaining("valid JSON");
    }
}
