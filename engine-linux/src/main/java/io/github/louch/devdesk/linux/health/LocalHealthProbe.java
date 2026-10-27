package io.github.louch.devdesk.linux.health;

import io.github.louch.devdesk.core.health.HealthProbe;
import io.github.louch.devdesk.core.health.ProbeResult;
import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.GracefulStop;
import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.ProcessLaunchSpec;
import io.github.louch.devdesk.core.process.ProcessRuntime;
import io.github.louch.devdesk.core.process.RunningProcess;
import io.github.louch.devdesk.core.project.HealthCheckSpec;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Probes services running on this machine. Every failure becomes a plain-words {@link ProbeResult.Failed}. */
public final class LocalHealthProbe implements HealthProbe {

    // Never follow redirects: a health URL that redirects elsewhere (e.g. to a login page) must not count as healthy.
    // HTTP/1.1 only: by default Java asks plain-http servers to upgrade to HTTP/2 ("Upgrade: h2c"). Dev servers with a
    // websocket upgrade handler (Vite, webpack-dev-server) take that request and never answer it, so a working server
    // looked unhealthy.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ProcessRuntime commands;

    /** {@code commands} runs command checks; it must stop the whole process group when a check times out. */
    public LocalHealthProbe(ProcessRuntime commands) {
        this.commands = commands;
    }

    @Override
    public ProbeResult probe(HealthCheckSpec check, Path directory, Map<String, String> environment) {
        return switch (check) {
            case HealthCheckSpec.ProcessAlive p -> new ProbeResult.Passed("process running");
            case HealthCheckSpec.Tcp tcp -> tcp(tcp);
            case HealthCheckSpec.Http h -> http(h);
            case HealthCheckSpec.Command c -> command(c, directory, environment);
            // The supervisor asks the container runtime for these; reaching here means a check was misrouted.
            case HealthCheckSpec.Docker d ->
                new ProbeResult.Failed("type: docker checks are answered by Docker, not here");
        };
    }

    private static ProbeResult tcp(HealthCheckSpec.Tcp check) {
        String target = check.host() + ":" + check.port();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(check.host(), check.port()), timeoutMillis(check.timing()));
            return new ProbeResult.Passed(target + " accepts connections");
        } catch (ConnectException e) {
            return new ProbeResult.Failed(target + " refused the connection (nothing listening yet?)");
        } catch (SocketTimeoutException e) {
            return new ProbeResult.Failed(target + " did not answer within "
                    + check.timing().timeout().toMillis() + " ms");
        } catch (IOException e) {
            return new ProbeResult.Failed(target + ": " + e.getMessage());
        }
    }

    private ProbeResult http(HealthCheckSpec.Http check) {
        HttpRequest request = HttpRequest.newBuilder(check.url())
                .timeout(check.timing().timeout())
                .GET()
                .build();
        try {
            int status =
                    http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            String detail = "GET " + check.url() + " → HTTP " + status;
            return status >= check.minStatus() && status <= check.maxStatus()
                    ? new ProbeResult.Passed(detail)
                    : new ProbeResult.Failed(detail + ", expected " + check.minStatus() + "-" + check.maxStatus());
        } catch (HttpTimeoutException e) {
            return new ProbeResult.Failed("GET " + check.url() + " did not answer within "
                    + check.timing().timeout().toMillis() + " ms");
        } catch (ConnectException e) {
            return new ProbeResult.Failed("GET " + check.url() + ": connection refused (nothing listening yet?)");
        } catch (IOException e) {
            return new ProbeResult.Failed("GET " + check.url() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProbeResult.Failed("interrupted");
        }
    }

    private ProbeResult command(HealthCheckSpec.Command check, Path directory, Map<String, String> environment) {
        AtomicReference<String> lastLine = new AtomicReference<>("");
        RunningProcess p;
        try {
            p = commands.start(
                    new ProcessLaunchSpec("health-check", check.command(), directory, environment),
                    line -> lastLine.set(line.text()));
        } catch (LaunchException e) {
            return new ProbeResult.Failed(e.getMessage());
        }
        try {
            ExitStatus exit = commands.onExit(p).get(check.timing().timeout().toMillis(), TimeUnit.MILLISECONDS);
            String detail = "`" + check.command() + "` " + exit.explanation()
                    + (lastLine.get().isBlank() ? "" : ": " + lastLine.get());
            return exit.code() == 0 ? new ProbeResult.Passed(detail) : new ProbeResult.Failed(detail);
        } catch (TimeoutException e) {
            stopQuietly(p); // a hanging check must not leave processes behind
            return new ProbeResult.Failed("`" + check.command() + "` did not finish within "
                    + check.timing().timeout().toMillis() + " ms");
        } catch (ExecutionException e) {
            return new ProbeResult.Failed(
                    "`" + check.command() + "`: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopQuietly(p);
            return new ProbeResult.Failed("interrupted");
        }
    }

    private void stopQuietly(RunningProcess p) {
        try {
            new GracefulStop(commands).stop(p, Duration.ZERO);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int timeoutMillis(HealthCheckSpec.Timing timing) {
        return Math.toIntExact(Math.max(1, timing.timeout().toMillis()));
    }
}
