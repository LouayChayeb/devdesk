package io.github.louch.devdesk.core.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.http.HttpSender.Outcome.Failed;
import io.github.louch.devdesk.core.http.HttpSender.Reason;
import io.github.louch.devdesk.core.state.ServiceState;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestTroubleTest {

    private static Failed failed(Reason r) {
        return new Failed(r, "low-level message", Duration.ofMillis(3));
    }

    @Test
    void refusedOnAStoppedServicesPortSaysToStartIt() throws Exception {
        var text = RequestTrouble.explain(
                failed(Reason.REFUSED), "http://localhost:3001/health", RequestVariablesTest.project(), Map.of());

        assertThat(text)
                .isEqualTo("Nothing listens on port 3001. It's api's port, and api is stopped: start it in Services.");
    }

    @Test
    void refusedWhileRunningPointsAtTheWrongPortOrIpv6() throws Exception {
        var text = RequestTrouble.explain(
                failed(Reason.REFUSED),
                "http://127.0.0.1:3001/",
                RequestVariablesTest.project(),
                Map.of("api", new ServiceState.Running(42, 0)));

        assertThat(text)
                .contains("api is running, but nothing listens on port 3001")
                .contains("Ports page");
    }

    @Test
    void refusedOnAnUnknownPortListsTheProjectsPorts() throws Exception {
        var text = RequestTrouble.explain(
                failed(Reason.REFUSED), "http://localhost:3000/", RequestVariablesTest.project(), Map.of());

        assertThat(text).contains("no service of this project declares it").contains("api 3001, db 55432");
    }

    @Test
    void containerPortsCountAsTheServicesPorts() throws Exception {
        var text = RequestTrouble.explain(
                failed(Reason.TIMEOUT), "http://localhost:55432/", RequestVariablesTest.project(), Map.of());

        assertThat(text).contains("db may be stuck");
    }

    @Test
    void aClosedConnectionPointsAtTheServicesLogs() throws Exception {
        var text = RequestTrouble.explain(
                failed(Reason.CLOSED), "http://localhost:3001/orders/abc", RequestVariablesTest.project(), Map.of());

        assertThat(text)
                .startsWith("api accepted the connection, then closed it")
                .contains("api's logs");
    }

    @Test
    void anUnknownHostSuggestsLocalhost() throws Exception {
        var text = RequestTrouble.explain(
                failed(Reason.UNKNOWN_HOST), "http://api:3001/", RequestVariablesTest.project(), Map.of());

        assertThat(text).contains("\"api\" doesn't resolve").contains("http://localhost:3001");
    }

    @Test
    void aLocalUrlIsMatchedToTheServiceOnItsPort() throws Exception {
        var project = RequestVariablesTest.project();
        assertThat(RequestTrouble.serviceAt("http://localhost:3001/orders", project))
                .contains("api");
        assertThat(RequestTrouble.serviceAt("http://127.0.0.1:55432", project)).contains("db");
        assertThat(RequestTrouble.serviceAt("http://example.com:3001/", project))
                .isEmpty();
        assertThat(RequestTrouble.serviceAt("not a url", project)).isEmpty();
    }
}
