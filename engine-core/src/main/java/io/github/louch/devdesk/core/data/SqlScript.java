package io.github.louch.devdesk.core.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a SQL script into statements at the semicolons that end them, so each can run on its own. Semicolons inside
 * quotes ('...', "...", `...`) and comments (-- and /* *&#47;) don't count.
 */
public final class SqlScript {

    private SqlScript() {}

    /**
     * @param backslashEscapes true for MySQL, where {@code 'it\'s'} is one string; in standard SQL (SQLite) a
     *     backslash is an ordinary character
     */
    public static List<String> split(String script, boolean backslashEscapes) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int n = script.length();
        int i = 0;
        while (i < n) {
            char c = script.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = closing(script, i, c, backslashEscapes);
                current.append(script, i, end);
                i = end;
            } else if (c == '-' && i + 1 < n && script.charAt(i + 1) == '-') {
                int end = script.indexOf('\n', i);
                i = end < 0 ? n : end; // a comment is dropped; the line break stays
            } else if (c == '/' && i + 1 < n && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (c == ';') {
                add(out, current);
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        add(out, current);
        return out;
    }

    /** The index just after the quote that closes the one at {@code start}; a doubled quote ('') is part of the text. */
    private static int closing(String s, int start, char quote, boolean backslashEscapes) {
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (backslashEscapes && c == '\\' && quote != '`' && i + 1 < s.length()) {
                i += 2; // MySQL's backslash escapes: \' doesn't end the string
            } else if (c == quote) {
                if (i + 1 < s.length() && s.charAt(i + 1) == quote) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        return s.length();
    }

    private static void add(List<String> out, StringBuilder current) {
        String statement = current.toString().strip();
        if (!statement.isEmpty()) {
            out.add(statement);
        }
        current.setLength(0);
    }
}
