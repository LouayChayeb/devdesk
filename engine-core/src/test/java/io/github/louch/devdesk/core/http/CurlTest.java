package io.github.louch.devdesk.core.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CurlTest {

    @Test
    void aCommandFromBrowserDevToolsIsRead() {
        var call = Curl.parse("""
                curl 'http://localhost:3001/api/orders?x=1' \\
                  -H 'accept: application/json' \\
                  -H "Authorization: Bearer abc" \\
                  --data-raw '{"name":"it'\\''s"}' \\
                  --compressed""");

        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.url()).isEqualTo("http://localhost:3001/api/orders?x=1");
        assertThat(call.headers()).extracting(HttpCall.Header::name).containsExactly("accept", "Authorization");
        assertThat(call.text()).contains("{\"name\":\"it's\"}");
    }

    @Test
    void formsFilesAndBasicAuth() {
        var call = Curl.parse("curl -u ada:pw -F 'avatar=@/tmp/a.png' -F note=hi http://h/upload");
        assertThat(call.auth()).isEqualTo(new HttpCall.Auth.Basic("ada", "pw"));
        assertThat(call.body())
                .isEqualTo(new HttpCall.Body.Multipart(List.of(
                        new HttpCall.Field("avatar", "/tmp/a.png", true), new HttpCall.Field("note", "hi", false))));
        assertThatThrownBy(() -> Curl.parse("wget http://x")).hasMessageContaining("start with curl");
        assertThatThrownBy(() -> Curl.parse("curl -s")).hasMessageContaining("no URL");
    }

    @Test
    void copyAsCurlRunsTheSameRequest() {
        var call = new HttpCall(
                "PUT",
                "http://localhost:3001/items/1",
                List.of(new HttpCall.Header("Content-Type", "application/json")),
                new HttpCall.Body.Text("{\"note\": \"it's\"}"),
                new HttpCall.Auth.Bearer("tok"));
        String curl = Curl.of(call);
        assertThat(curl)
                .isEqualTo("curl -X PUT 'http://localhost:3001/items/1' -H 'Content-Type: application/json'"
                        + " -H 'Authorization: Bearer tok' --data-raw '{\"note\": \"it'\\''s\"}'");
        // And it reads back as the same request (auth now as a plain header).
        var back = Curl.parse(curl);
        assertThat(back.method()).isEqualTo("PUT");
        assertThat(back.text()).contains("{\"note\": \"it's\"}");
        assertThat(Curl.of(new HttpCall("GET", "http://x/", List.of(), Optional.empty())))
                .isEqualTo("curl 'http://x/'");
    }
}
