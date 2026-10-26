package io.github.louch.devdesk.data;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Fuzz test (Jazzer) for the Redis reply parser. The other end may not be Redis at all (a wrong port), so any bytes
 * must give a reply or an IOException, which the browser shows as "lost the connection". Never a crash or a hang.
 */
class RespFuzzTest {

    @FuzzTest(maxDuration = "10s")
    void reply(FuzzedDataProvider data) {
        byte[] bytes = data.consumeRemainingAsBytes();
        try {
            Resp.readReply(new ByteArrayInputStream(bytes));
        } catch (IOException expected) {
            // not a valid reply
        }
    }
}
