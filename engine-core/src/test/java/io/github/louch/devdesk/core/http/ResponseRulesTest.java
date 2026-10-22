package io.github.louch.devdesk.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.http.ResponseRules.Check;
import io.github.louch.devdesk.core.http.ResponseRules.Op;
import io.github.louch.devdesk.core.http.ResponseRules.Part;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResponseRulesTest {

    private static final HttpSender.Outcome.Answered ANSWER = new HttpSender.Outcome.Answered(
            201,
            "HTTP/1.1",
            List.of(new HttpCall.Header("Content-Type", "application/json")),
            "{\"access_token\": \"abc\", \"user\": {\"id\": 7, \"roles\": [\"admin\", \"dev\"]}}",
            false,
            80,
            Duration.ofMillis(42));

    @Test
    void readsStatusHeadersAndJsonPaths() {
        assertThat(ResponseRules.read(ANSWER, Part.STATUS, "")).contains("201");
        assertThat(ResponseRules.read(ANSWER, Part.HEADER, "content-type")).contains("application/json");
        assertThat(ResponseRules.read(ANSWER, Part.JSON, "access_token")).contains("abc");
        assertThat(ResponseRules.read(ANSWER, Part.JSON, "$.user.roles[1]")).contains("dev");
        assertThat(ResponseRules.read(ANSWER, Part.JSON, "user.id")).contains("7");
        assertThat(ResponseRules.read(ANSWER, Part.JSON, "user")).contains("{\"id\":7,\"roles\":[\"admin\",\"dev\"]}");
        assertThat(ResponseRules.read(ANSWER, Part.JSON, "user.nope")).isEmpty();
        assertThat(ResponseRules.read(ANSWER, Part.JSON, "user.roles[9]")).isEmpty();
    }

    @Test
    void checksPassOrSayWhatWasThere() {
        assertThat(ResponseRules.check(ANSWER, new Check(Part.STATUS, "", Op.EQUALS, "200")))
                .satisfies(r -> {
                    assertThat(r.passed()).isFalse();
                    assertThat(r.actual()).isEqualTo("201");
                });
        assertThat(ResponseRules.checkAll(
                        ANSWER,
                        List.of(
                                new Check(Part.JSON, "user.id", Op.EXISTS, ""),
                                new Check(Part.TIME, "", Op.LESS, "500"),
                                new Check(Part.BODY, "", Op.CONTAINS, "admin"),
                                new Check(Part.HEADER, "X-Nope", Op.NOT_EQUALS, "x"))))
                .allMatch(ResponseRules.Result::passed);
        assertThat(ResponseRules.check(ANSWER, new Check(Part.JSON, "missing", Op.EXISTS, ""))
                        .actual())
                .isEqualTo("missing");
        assertThat(ResponseRules.describe(new Check(Part.JSON, "user.id", Op.EQUALS, "7")))
                .isEqualTo("json user.id equals 7");
    }
}
