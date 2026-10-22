package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.http.HttpCall;
import io.github.louch.devdesk.core.http.HttpCallJson;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HttpCallParsingTest {

    @Test
    void aRequestFromTheAppIsValidated() {
        var call = HttpCallJson.read(Map.of(
                        "method", "post",
                        "url", " {{api.url}}/orders ",
                        "headers", List.of(Map.of("name", "X-A", "value", "1"), Map.of("name", " ", "value", "skip")),
                        "body", "{}"))
                .orElseThrow();

        assertThat(call)
                .isEqualTo(new HttpCall(
                        "POST", "{{api.url}}/orders", List.of(new HttpCall.Header("X-A", "1")), Optional.of("{}")));
        assertThat(HttpCallJson.read(Map.of("method", "BREW", "url", "x"))).isEmpty();
        assertThat(HttpCallJson.read(Map.of("method", "GET"))).isEmpty();
        assertThat(HttpCallJson.read(Map.of("method", "GET", "url", "x", "headers", List.of("oops"))))
                .isEmpty();
    }
}
