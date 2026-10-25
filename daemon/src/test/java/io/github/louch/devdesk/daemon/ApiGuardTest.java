package io.github.louch.devdesk.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The API's three checks (Host, Origin, token) in-process; ApiSecurityTest proves them over real HTTP. */
class ApiGuardTest {

    private static final byte[] TOKEN = "s3cret-token".getBytes(StandardCharsets.UTF_8);
    private static final String PATH = "/api/v1/project";

    private static Optional<ApiServer.Refusal> check(
            String host, String origin, String method, String path, String auth) {
        return ApiServer.refusal(host, origin, method, path, auth, TOKEN);
    }

    @Test
    void theRightTokenFromTheAppOrCurlGoesThrough() {
        assertThat(check("127.0.0.1:41234", null, "GET", PATH, "Bearer s3cret-token"))
                .isEmpty();
        assertThat(check("localhost", "tauri://localhost", "POST", PATH, "Bearer s3cret-token"))
                .isEmpty();
        assertThat(check("localhost:9", "http://localhost:4200", "GET", PATH, "s3cret-token"))
                .isEmpty();
    }

    @Test
    void aMissingOrWrongTokenIs401() {
        var missing = new ApiServer.Refusal(401, "missing or wrong token");
        assertThat(check("127.0.0.1", null, "GET", PATH, null)).contains(missing);
        assertThat(check("127.0.0.1", null, "GET", PATH, "Bearer s3cret-toke")).contains(missing);
        assertThat(check("127.0.0.1", null, "GET", PATH, "Bearer s3cret-token-and-more"))
                .contains(missing);
        assertThat(check("127.0.0.1", null, "GET", PATH, "Bearer ")).contains(missing);
    }

    @Test
    void onlyPreflightsAndTheTwoSocketsSkipTheHeaderToken() {
        assertThat(check("127.0.0.1", "tauri://localhost", "OPTIONS", PATH, null))
                .isEmpty();
        assertThat(check("127.0.0.1", null, "GET", "/api/v1/events", null)).isEmpty(); // checked on connect
        assertThat(check("127.0.0.1", null, "GET", "/api/v1/terminal", null)).isEmpty();
        assertThat(check("127.0.0.1", null, "GET", "/api/v1/events/x", null))
                .map(ApiServer.Refusal::status)
                .contains(401);
    }

    @Test
    void aForeignHostIs403EvenWithTheToken() {
        // DNS rebinding: evil.example resolved to 127.0.0.1 still says Host: evil.example.
        for (String host :
                new String[] {"evil.example", "evil.example:41234", "127.0.0.1.evil.example", "0.0.0.0", ""}) {
            assertThat(check(host, null, "GET", PATH, "Bearer s3cret-token"))
                    .as(host)
                    .contains(new ApiServer.Refusal(403, "wrong Host header: DevDesk only answers on 127.0.0.1"));
        }
        assertThat(check(null, null, "GET", PATH, "Bearer s3cret-token"))
                .map(ApiServer.Refusal::status)
                .contains(403);
    }

    @Test
    void aWebPageThatIsntTheAppIs403EvenWithTheToken() {
        assertThat(check("127.0.0.1", "https://evil.example", "GET", PATH, "Bearer s3cret-token"))
                .contains(new ApiServer.Refusal(403, "requests from https://evil.example are not allowed"));
        assertThat(check("127.0.0.1", "http://localhost:4201", "OPTIONS", PATH, null))
                .map(ApiServer.Refusal::status)
                .contains(403);
        assertThat(check("127.0.0.1", "null", "GET", "/api/v1/events", null)) // a sandboxed iframe
                .map(ApiServer.Refusal::status)
                .contains(403);
    }

    @Test
    void theTokenComparisonNeedsTheWholeToken() {
        assertThat(ApiServer.tokenMatches(TOKEN, "s3cret-token")).isTrue();
        assertThat(ApiServer.tokenMatches(TOKEN, "S3CRET-TOKEN")).isFalse();
        assertThat(ApiServer.tokenMatches(TOKEN, "")).isFalse();
        assertThat(ApiServer.tokenMatches(TOKEN, null)).isFalse();
    }
}
