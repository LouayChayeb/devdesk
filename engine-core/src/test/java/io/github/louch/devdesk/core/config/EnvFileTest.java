package io.github.louch.devdesk.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** .env files as found in real projects: comments, export, quotes, odd lines. */
class EnvFileTest {

    @Test
    void readsKeysAndValuesSkippingBlankLinesAndComments() {
        var vars = EnvFile.parse("""
                # a comment
                   # an indented comment
                #COMMENTED=out

                PORT=3000
                  NAME = shop  \s
                export TOKEN=abc
                """);

        assertThat(vars)
                .containsExactly(
                        java.util.Map.entry("PORT", "3000"),
                        java.util.Map.entry("NAME", "shop"),
                        java.util.Map.entry("TOKEN", "abc"));
    }

    @Test
    void linesThatAreNotKeyValueAreIgnored() {
        var vars = EnvFile.parse("=no-key\njust words\nEMPTY=\nURL=a=b=c\n");

        assertThat(vars).containsExactly(java.util.Map.entry("EMPTY", ""), java.util.Map.entry("URL", "a=b=c"));
    }

    @Test
    void matchingQuotesAreRemovedOthersKept() {
        var vars = EnvFile.parse("""
                D="double quoted"
                S='single quoted'
                E=""
                ONE="
                MIX="half'
                INNER=say "hi"
                """);

        assertThat(vars)
                .containsEntry("D", "double quoted")
                .containsEntry("S", "single quoted")
                .containsEntry("E", "")
                .containsEntry("ONE", "\"") // a lone quote isn't a quoted value
                .containsEntry("MIX", "\"half'")
                .containsEntry("INNER", "say \"hi\"");
    }

    @Test
    void windowsLineEndingsDoNotEndUpInValues() {
        assertThat(EnvFile.parse("A=1\r\nB=2\r\n")).containsEntry("A", "1").containsEntry("B", "2");
    }
}
