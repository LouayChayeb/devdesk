package io.github.louch.devdesk.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;

/**
 * Fuzz tests (Jazzer) for what the API client imports: pasted curl commands, Postman collections and OpenAPI
 * descriptions. Bad input must give an IllegalArgumentException that the app shows; anything else is a crash.
 */
class HttpImportFuzzTest {

    /** A pasted curl command; what "copy as curl" writes for it must read back as the same request. */
    @FuzzTest(maxDuration = "10s")
    void curl(FuzzedDataProvider data) {
        String command = data.consumeRemainingAsString();
        HttpCall call;
        try {
            call = Curl.parse(command);
        } catch (IllegalArgumentException expected) {
            return;
        }
        HttpCall again = Curl.parse(Curl.of(call));
        assertThat(again.method()).isEqualTo(call.method());
        assertThat(again.url()).isEqualTo(call.url());
    }

    @FuzzTest(maxDuration = "10s")
    void postman(FuzzedDataProvider data) {
        String json = data.consumeRemainingAsString();
        try {
            RequestImports.postman(json);
        } catch (IllegalArgumentException expected) {
            // not a Postman collection
        }
    }

    @FuzzTest(maxDuration = "10s")
    void openApi(FuzzedDataProvider data) {
        String json = data.consumeRemainingAsString();
        try {
            RequestImports.openApi(json, "{{api.url}}");
        } catch (IllegalArgumentException expected) {
            // not an OpenAPI description
        }
    }
}
