package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.louch.devdesk.core.stress.LoadPlan;
import io.github.louch.devdesk.core.stress.LoadSecond;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Real load on a real local server that starts failing after a number of calls. */
class JdkLoadGeneratorTest {

    @Test
    void reportsEachSecondWithErrorsWhenTheServiceGivesUp() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/", ex -> {
            int n = calls.incrementAndGet();
            ex.sendResponseHeaders(n > 300 ? 503 : 200, -1);
            ex.close();
        });
        service.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        service.start();
        try {
            List<LoadSecond> seconds = new CopyOnWriteArrayList<>();
            var plan = new LoadPlan(
                    "GET", "http://127.0.0.1:" + service.getAddress().getPort() + "/", Map.of(), "", 8, 5);
            new JdkLoadGenerator().run(plan, seconds::add, () -> false);
            assertThat(seconds).hasSize(5);
            assertThat(seconds).extracting(LoadSecond::concurrency).containsExactly(2, 4, 5, 7, 8);
            assertThat(seconds.stream().mapToInt(LoadSecond::requests).sum()).isGreaterThan(300);
            assertThat(seconds.getLast().errors()).isPositive();
            assertThat(seconds.getLast().errorKinds()).containsKey("503");
            assertThat(seconds.getFirst().p95Ms())
                    .isGreaterThanOrEqualTo(seconds.getFirst().p50Ms());
        } finally {
            service.stop(0);
        }
    }

    @Test
    void stopsWhenCancelled() throws Exception {
        HttpServer service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        service.start();
        try {
            List<LoadSecond> seconds = new CopyOnWriteArrayList<>();
            var plan = new LoadPlan(
                    "GET", "http://127.0.0.1:" + service.getAddress().getPort() + "/", Map.of(), "", 2, 60);
            new JdkLoadGenerator().run(plan, seconds::add, () -> seconds.size() >= 2);
            assertThat(seconds).hasSize(2);
        } finally {
            service.stop(0);
        }
    }
}
