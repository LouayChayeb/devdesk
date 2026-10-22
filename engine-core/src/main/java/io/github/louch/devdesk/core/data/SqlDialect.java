package io.github.louch.devdesk.core.data;

/** How each SQL database writes names and text values, for the SQL that DevDesk generates (exports, edits). */
public enum SqlDialect {
    POSTGRES,
    MYSQL,
    SQLITE;

    public static SqlDialect of(DatabaseTarget.Kind kind) {
        return switch (kind) {
            case POSTGRES -> POSTGRES;
            case MYSQL -> MYSQL;
            case SQLITE -> SQLITE;
            case REDIS, MONGO, OBJECTS, SEARCH -> throw new IllegalArgumentException(kind.label() + " has no SQL");
        };
    }

    /** {@code my"table} → {@code "my""table"}; MySQL uses backticks unless it runs in ANSI mode. */
    public String name(String identifier) {
        return this == MYSQL ? '`' + identifier.replace("`", "``") + '`' : '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /** "schema"."table", or just "table" when there is no schema (SQLite's main database). */
    public String table(String schema, String table) {
        return schema.isEmpty() || (this == SQLITE && schema.equals("main"))
                ? name(table)
                : name(schema) + "." + name(table);
    }

    /**
     * A text value as a SQL string: {@code it's} → {@code 'it''s'}. MySQL also reads backslashes as escapes by default
     * (unlike the SQL standard), so for MySQL they are doubled too.
     */
    public String literal(String value) {
        String v = value.replace("'", "''");
        if (this == MYSQL) {
            v = v.replace("\\", "\\\\");
        }
        return "'" + v + "'";
    }
}
