package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.stress.LoadGenerator;
import io.github.louch.devdesk.core.stress.LoadPlan;
import io.github.louch.devdesk.core.stress.LoadSecond;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * {@link LoadGenerator} with virtual threads: one per caller, each sending the request again as soon as it has its
 * answer. Every second more callers join (the plan's climb), so the first seconds show normal speed and the last ones
 * the limit.
 */
public final class JdkLoadGenerator implements LoadGenerator {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** What one second collected: latencies and error kinds, filled by many callers at once. */
    private static final class Bucket {
        final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        final Map<String, AtomicInteger> errors = new ConcurrentHashMap<>();
    }

    @Override
    public void run(LoadPlan plan, Consumer<LoadSecond> each, BooleanSupplier cancelled) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(plan.url()))
                .timeout(TIMEOUT)
                .method(
                        plan.method(),
                        plan.body().isEmpty()
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(plan.body()));
        plan.headers().forEach(b::header);
        HttpRequest request = b.build();
        Map<Integer, Bucket> buckets = new ConcurrentHashMap<>();
        AtomicInteger callers = new AtomicInteger(); // how many may run now: raised each second
        long start = System.nanoTime();
        long end = start + Duration.ofSeconds(plan.seconds()).toNanos();
        List<Thread> threads = new ArrayList<>();
        try {
            for (int s = 0; s < plan.seconds() && !cancelled.getAsBoolean(); s++) {
                int want = plan.concurrencyAt(s);
                callers.set(want);
                while (threads.size() < want) {
                    int index = threads.size();
                    threads.add(Thread.ofVirtual().name("load-" + index).start(() -> {
                        // A caller sends while it's among the allowed ones, the time isn't up, and nobody cancelled.
                        while (index < callers.get() && System.nanoTime() < end && !cancelled.getAsBoolean()) {
                            send(http, request, start, buckets);
                        }
                    }));
                }
                sleepUntil(start + Duration.ofSeconds(s + 1).toNanos());
                each.accept(second(s, want, buckets.remove(s)));
            }
        } finally {
            callers.set(0);
            threads.forEach(Thread::interrupt);
        }
    }

    private static void send(HttpClient http, HttpRequest request, long start, Map<Integer, Bucket> buckets) {
        long t0 = System.nanoTime();
        String error = null;
        try {
            HttpResponse<Void> r = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (r.statusCode() >= 400) {
                error = Integer.toString(r.statusCode());
            }
        } catch (HttpTimeoutException e) {
            error = "timeout";
        } catch (ConnectException e) {
            error = "refused";
        } catch (IOException e) {
            error = "connection closed";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        long t1 = System.nanoTime();
        // Counted in the second the answer came back.
        Bucket b = buckets.computeIfAbsent((int) ((t1 - start) / 1_000_000_000L), k -> new Bucket());
        b.latencies.add((t1 - t0) / 1_000_000L);
        if (error != null) {
            b.errors.computeIfAbsent(error, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    private static LoadSecond second(int s, int concurrency, Bucket b) {
        if (b == null) {
            return new LoadSecond(s, concurrency, 0, 0, 0, 0, 0, Map.of());
        }
        List<Long> l;
        synchronized (b.latencies) {
            l = new ArrayList<>(b.latencies);
        }
        Collections.sort(l);
        Map<String, Integer> kinds = new TreeMap<>();
        b.errors.forEach((k, v) -> kinds.put(k, v.get()));
        int errors = kinds.values().stream().mapToInt(Integer::intValue).sum();
        return new LoadSecond(
                s,
                concurrency,
                l.size(),
                errors,
                percentile(l, 0.50),
                percentile(l, 0.95),
                l.isEmpty() ? 0 : l.getLast(),
                kinds);
    }

    private static double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }

    private static void sleepUntil(long nanoTime) {
        long left = nanoTime - System.nanoTime();
        if (left > 0) {
            try {
                Thread.sleep(Duration.ofNanos(left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
