package io.github.louch.devdesk.daemon;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

/**
 * The real engine as its own program, like the app runs it, with a throwaway home (trust list, state, settings).
 * Closing it closes its stdin: the engine stops everything and exits, as when the app quits.
 */
record TestEngine(Process process, int port, String base, String token, HttpClient http) implements AutoCloseable {

    static TestEngine start(Path home, String extras) throws Exception {
        return start(home, extras, false);
    }

    /** @param noSystemd hide the user's systemd from the engine: it then owns services by process group */
    static TestEngine start(Path home, String extras, boolean noSystemd) throws Exception {
        return start(home, extras, noSystemd, java.util.List.of());
    }

    /** @param jvmOptions extra JVM options, e.g. the crash-point properties */
    static TestEngine start(Path home, String extras, boolean noSystemd, java.util.List<String> jvmOptions)
            throws Exception {
        return start(home, extras, noSystemd, jvmOptions, Map.of());
    }

    /** @param env extra environment for the engine, e.g. the image builder tests use (BUILDX_BUILDER) */
    static TestEngine start(
            Path home, String extras, boolean noSystemd, java.util.List<String> jvmOptions, Map<String, String> env)
            throws Exception {
        var command = new java.util.ArrayList<>(java.util.List.of("java", "--enable-native-access=ALL-UNNAMED"));
        command.addAll(jvmOptions);
        command.addAll(java.util.List.of("-cp", System.getProperty("java.class.path"), Main.class.getName()));
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("XDG_STATE_HOME", home.resolve("state").toString());
        builder.environment().remove("XDG_CONFIG_HOME");
        builder.environment().remove(Extras.ENV);
        if (noSystemd) {
            builder.environment().remove("XDG_RUNTIME_DIR");
            builder.environment().remove("DBUS_SESSION_BUS_ADDRESS");
        }
        if (extras != null) {
            builder.environment().put(Extras.ENV, extras);
        }
        builder.environment().putAll(env);
        Process p = builder.start();
        var reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
        Map<?, ?> hello = ApiJson.MAPPER.readValue(reader.readLine(), Map.class);
        int port = ((Number) hello.get("port")).intValue();
        return new TestEngine(
                p,
                port,
                "http://127.0.0.1:" + port + "/api/v1",
                (String) hello.get("token"),
                HttpClient.newHttpClient());
    }

    HttpResponse<String> call(String route) throws Exception {
        return call(route, null);
    }

    HttpResponse<String> call(String route, String body) throws Exception {
        String[] parts = route.split(" ", 2);
        var request = HttpRequest.newBuilder(URI.create(base + parts[1]))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(
                        parts[0],
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A hand-written HTTP request over a plain socket, for headers Java's client won't let a program set (Host).
     * Returns the whole response: status line, headers and body.
     */
    String raw(String request) throws java.io.IOException {
        try (var socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Override
    public void close() throws java.io.IOException {
        // Closing its stdin is how the app says goodbye: the engine stops everything and exits.
        process.getOutputStream().close();
        try {
            if (!process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly(); // our own child process, started above
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
