package io.github.louch.devdesk.data;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;

/**
 * Any image in a throwaway container, its port published on a free host port. Pulls the image when missing; skips
 * the test only without Docker.
 */
final class TestContainer implements AutoCloseable {

    final int port;
    private final String name;

    private TestContainer(String name, int port) {
        this.name = name;
        this.port = port;
    }

    /**
     * @param ready called with the host port until it returns true (at most 90 s)
     * @param env "NAME=value" settings
     */
    static TestContainer start(String label, String image, int containerPort, IntPredicate ready, String... env)
            throws InterruptedException {
        return start(label, image, containerPort, 0, ready, env);
    }

    /** @param hostPort a fixed host port (0 = any free one), for servers that must know their own address (Kafka) */
    static TestContainer start(
            String label, String image, int containerPort, int hostPort, IntPredicate ready, String... env)
            throws InterruptedException {
        assumeTrue(run("docker", "info").isPresent(), "Docker is not available");
        if (run("docker", "image", "inspect", image).isEmpty()) {
            // Pulled once (CI starts empty); a failed pull fails the test instead of silently skipping it.
            run("docker", "pull", "-q", image)
                    .orElseThrow(() -> new IllegalStateException("could not pull " + image + " (offline?)"));
        }
        String name = "devdesk-test-" + label + "-" + ProcessHandle.current().pid();
        List<String> cmd = new ArrayList<>(List.of("docker", "run", "-d", "--rm", "--name", name));
        for (String e : env) {
            cmd.addAll(List.of("-e", e));
        }
        cmd.addAll(List.of("-p", "127.0.0.1:" + (hostPort == 0 ? "" : hostPort) + ":" + containerPort, image));
        run(cmd.toArray(String[]::new)).orElseThrow();
        String mapped = run("docker", "port", name, containerPort + "/tcp")
                .orElseThrow()
                .strip()
                .split("\n")[0];
        int port = Integer.parseInt(mapped.substring(mapped.lastIndexOf(':') + 1));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (!ready.test(port)) {
            if (System.nanoTime() > deadline) {
                String logs = run("docker", "logs", "--tail", "30", name).orElse("(no logs)");
                run("docker", "rm", "-f", name);
                throw new IllegalStateException(image + " wasn't ready after 90 s. Its last output:\n" + logs);
            }
            Thread.sleep(500);
        }
        return new TestContainer(name, port);
    }

    /** A port nothing listens on right now. */
    static int freePort() {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        run("docker", "rm", "-f", name);
    }

    private static Optional<String> run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
