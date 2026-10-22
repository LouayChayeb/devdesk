package io.github.louch.devdesk.core.data;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rows to and from files: CSV, JSON and SQL inserts. Writing streams row by row (see {@link SqlBrowser.RowSink});
 * reading CSV gives rows ready to insert. NULL and an empty string stay different in every format.
 */
public final class TableFile {

    /** The file formats of export and import. */
    public enum Format {
        /** Comma-separated, header row first, RFC 4180 quoting. An unquoted empty cell is NULL; {@code ""} is empty text. */
        CSV,
        /** An array of objects, one per row; numbers, booleans and JSON columns keep their JSON type. */
        JSON,
        /** One INSERT statement per row, for running in another database. */
        SQL;

        public String extension() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Column types (as JDBC names them) written as JSON numbers, not strings. */
    private static final Set<String> NUMBERS = Set.of(
            "int2",
            "int4",
            "int8",
            "float4",
            "float8",
            "numeric",
            "smallint",
            "integer",
            "int",
            "bigint",
            "decimal",
            "real",
            "double",
            "float",
            "tinyint",
            "mediumint",
            "serial",
            "bigserial",
            "oid");

    private static final Set<String> BOOLEANS = Set.of("bool", "boolean");
    private static final Set<String> JSONS = Set.of("json", "jsonb");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TableFile() {}

    /**
     * A sink that writes each row to {@code out} in this format. {@code table} names the target of SQL inserts.
     *
     * @return the sink; {@link Writing#finish()} must be called after the last row (JSON closes its array)
     */
    public static Writing writer(Format format, Writer out, SqlDialect dialect, String schema, String table) {
        return new Writing(format, out, dialect, dialect.table(schema, table));
    }

    /** Writes rows as they come and counts them. */
    public static final class Writing implements SqlBrowser.RowSink {
        private final Format format;
        private final Writer out;
        private final SqlDialect dialect;
        private final String table;
        private List<String> names = List.of();
        private List<String> types = List.of();
        private long rows;

        private Writing(Format format, Writer out, SqlDialect dialect, String table) {
            this.format = format;
            this.out = out;
            this.dialect = dialect;
            this.table = table;
        }

        @Override
        public void columns(List<String> names, List<String> types) {
            this.names = List.copyOf(names);
            this.types = types.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList();
            switch (format) {
                case CSV ->
                    write(String.join(
                                    ",", names.stream().map(n -> csv(n, false)).toList()) + "\r\n");
                case JSON -> write("[");
                case SQL -> {
                    // nothing before the first insert
                }
            }
        }

        @Override
        public void row(List<String> values) {
            switch (format) {
                case CSV -> {
                    List<String> cells = new ArrayList<>();
                    for (String v : values) {
                        cells.add(v == null ? "" : csv(v, true));
                    }
                    write(String.join(",", cells) + "\r\n");
                }
                case JSON -> {
                    StringBuilder b = new StringBuilder(rows == 0 ? "\n  {" : ",\n  {");
                    for (int i = 0; i < values.size(); i++) {
                        b.append(i == 0 ? "" : ", ")
                                .append(jsonString(names.get(i)))
                                .append(": ");
                        b.append(jsonValue(values.get(i), types.get(i)));
                    }
                    write(b.append('}').toString());
                }
                case SQL -> {
                    List<String> cols = names.stream().map(dialect::name).toList();
                    List<String> vals = values.stream()
                            .map(v -> v == null ? "NULL" : dialect.literal(v))
                            .toList();
                    write("INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
                            + String.join(", ", vals) + ");\n");
                }
            }
            rows++;
        }

        /** Ends the file. @return how many rows were written */
        public long finish() {
            if (format == Format.JSON) {
                write(rows == 0 ? "]\n" : "\n]\n");
            }
            return rows;
        }

        private void write(String s) {
            try {
                out.write(s);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** Quotes a CSV cell when it needs it; an empty text value is always quoted, so it doesn't read back as NULL. */
    static String csv(String v, boolean quoteEmpty) {
        boolean needs = (quoteEmpty && v.isEmpty())
                || v.indexOf(',') >= 0
                || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0
                || v.indexOf('\r') >= 0;
        return needs ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    private static String jsonValue(String v, String type) {
        if (v == null) {
            return "null";
        }
        if (JSONS.contains(type)) {
            return v; // already JSON text, from the database
        }
        if (BOOLEANS.contains(type)) {
            return v.equals("t") || v.equalsIgnoreCase("true") || v.equals("1") ? "true" : "false";
        }
        if (NUMBERS.contains(type) && v.matches("-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) {
            return v; // NaN and Infinity aren't JSON numbers; they stay strings
        }
        return jsonString(v);
    }

    /** A JSON string with the escapes JSON requires. */
    public static String jsonString(String s) {
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /**
     * Reads a CSV file whose first line names the columns. Each row maps column → value; empty = NULL, unless quoted.
     *
     * @throws DataException when a quote isn't closed or a row has more cells than the header
     */
    public static List<Map<String, Optional<String>>> readCsv(String text) {
        List<List<Optional<String>>> lines = csvLines(text.startsWith("﻿") ? text.substring(1) : text);
        if (lines.isEmpty()) {
            throw new DataException("the CSV file is empty: its first line must name the columns");
        }
        List<String> header = lines.getFirst().stream().map(c -> c.orElse("")).toList();
        if (header.stream().anyMatch(String::isBlank)) {
            throw new DataException("the CSV header (first line) has an empty column name: " + header);
        }
        List<Map<String, Optional<String>>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<Optional<String>> cells = lines.get(i);
            if (cells.size() == 1 && cells.getFirst().isEmpty() && header.size() > 1) {
                continue; // a blank line
            }
            if (cells.size() > header.size()) {
                throw new DataException("line " + (i + 1) + " of the CSV has " + cells.size() + " cells but the header"
                        + " names " + header.size() + " columns");
            }
            Map<String, Optional<String>> row = new LinkedHashMap<>();
            for (int c = 0; c < cells.size(); c++) {
                row.put(header.get(c), cells.get(c));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Reads a JSON array of objects, one per row. null is NULL; numbers and booleans become their text; nested
     * objects and arrays become JSON text (for json columns).
     *
     * @throws DataException when it isn't an array of objects
     */
    public static List<Map<String, Optional<String>>> readJson(String text) {
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JacksonException e) {
            throw new DataException("the file isn't valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isArray()) {
            throw new DataException("the JSON must be an array of objects, one per row: [{\"id\": 1, ...}, ...]");
        }
        List<Map<String, Optional<String>>> rows = new ArrayList<>();
        int i = 0;
        for (JsonNode item : root) {
            i++;
            if (!item.isObject()) {
                throw new DataException("item " + i + " of the JSON array isn't an object");
            }
            Map<String, Optional<String>> row = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : item.properties()) {
                JsonNode v = e.getValue();
                row.put(
                        e.getKey(),
                        v.isNull() ? Optional.empty() : Optional.of(v.isContainer() ? v.toString() : v.asString()));
            }
            rows.add(row);
        }
        return rows;
    }

    /** RFC 4180: cells split by commas, quoted cells may hold commas, quotes ("") and line breaks. */
    private static List<List<Optional<String>>> csvLines(String text) {
        List<List<Optional<String>>> lines = new ArrayList<>();
        List<Optional<String>> line = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < n && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
                wasQuoted = true;
            } else if (c == ',') {
                line.add(value(cell, wasQuoted));
                cell.setLength(0);
                wasQuoted = false;
            } else if (c == '\n' || c == '\r') {
                line.add(value(cell, wasQuoted));
                lines.add(line);
                line = new ArrayList<>();
                cell.setLength(0);
                wasQuoted = false;
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                cell.append(c);
            }
            i++;
        }
        if (quoted) {
            throw new DataException("the CSV has a quote (\") that is never closed");
        }
        if (!cell.isEmpty() || wasQuoted || !line.isEmpty()) {
            line.add(value(cell, wasQuoted));
            lines.add(line);
        }
        return lines;
    }

    private static Optional<String> value(StringBuilder cell, boolean quoted) {
        return cell.isEmpty() && !quoted ? Optional.empty() : Optional.of(cell.toString());
    }
}
