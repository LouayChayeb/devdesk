package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SigV4Test {

    /** AWS's own published test case ("get-vanilla" of the Signature Version 4 test suite). */
    @Test
    void matchesAwsPublishedExample() {
        var headers = SigV4.sign(
                "GET",
                "/",
                Map.of(),
                Map.of("Host", "example.amazonaws.com"),
                SigV4.EMPTY_HASH,
                "AKIDEXAMPLE",
                "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
                "us-east-1",
                "service",
                Instant.parse("2015-08-30T12:36:00Z"));
        assertThat(headers.get("Authorization"))
                .isEqualTo("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request,"
                        + " SignedHeaders=host;x-amz-date,"
                        + " Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
    }

    @Test
    void keysAreEncodedPartByPart() {
        assertThat(SigV4.encodePath("invoices/2026/a b+é.pdf")).isEqualTo("invoices/2026/a%20b%2B%C3%A9.pdf");
        assertThat(SigV4.encode("a/b")).isEqualTo("a%2Fb");
    }
}
