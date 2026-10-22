package io.github.louch.devdesk.core.data;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlScriptTest {

    @Test
    void splitsAtSemicolonsOutsideQuotesAndComments() {
        assertThat(SqlScript.split("""
                -- a comment; with a semicolon
                insert into t values ('a;b', 'it''s');
                /* block; comment */ insert into "we;ird" values (1);

                select 'x\\';y' ;
                select 1
                """, true))
                .containsExactly(
                        "insert into t values ('a;b', 'it''s')",
                        "insert into \"we;ird\" values (1)",
                        "select 'x\\';y'",
                        "select 1");
    }

    @Test
    void inStandardSqlABackslashIsJustACharacter() {
        assertThat(SqlScript.split("insert into t values ('C:\\'); select 2", false))
                .containsExactly("insert into t values ('C:\\')", "select 2");
    }
}
