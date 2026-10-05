package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.diagnosis.DiagnosisEngine;
import io.github.louch.devdesk.docker.DockerConnection;
import io.github.louch.devdesk.linux.diagnosis.LinuxIntrospection;
import io.github.louch.devdesk.linux.health.LocalHealthProbe;
import io.github.louch.devdesk.linux.network.ProcNetListeners;
import io.github.louch.devdesk.linux.process.LinuxProcessRuntime;
import io.github.louch.devdesk.linux.process.ScopeLeftovers;
import io.github.louch.devdesk.linux.resources.ProcResources;
import io.github.louch.devdesk.linux.trust.TrustStore;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;

/**
 * Entry point of {@code devdeskd}, the engine the desktop app starts. Wires every dependency by hand.
 *
 * <p>It prints one line, {@code {"port":...,"token":...}}, so the app knows where to connect. It stops every service
 * and exits when its stdin closes, which happens when the app exits or crashes (ADR 0005: close DevDesk = stop all).
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) throws Exception {
        var env = System.getenv();
        var clock = Clock.systemUTC();
        var random = new SecureRandom();
        var listeners = new ProcNetListeners();
        var engine = new EngineSession.Engine(
                LinuxProcessRuntime.detect(clock, random),
                () -> DockerConnection.connect(env),
                new LocalHealthProbe(new LinuxProcessRuntime(false, clock, random)),
                new LinuxIntrospection(listeners),
                DiagnosisEngine.standard(),
                new ScopeLeftovers(),
                new TrustStore(TrustStore.defaultFile(env)),
                new ProcResources(),
                env,
                clock,
                random);
        var session = new EngineSession(engine);

        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = HexFormat.of().formatHex(secret);
        var server = new ApiServer(session, token);
        int port = server.start();

        // Ctrl+C, SIGTERM, or the stdin lifeline below: stop every service (dependents first), then the server.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            session.close();
            server.stop();
        }));

        System.out.println("{\"port\":" + port + ",\"token\":\"" + token + "\"}");
        System.out.flush();

        if (args.length == 0 || !args[0].equals("--no-stdin-lifeline")) {
            watchStdin();
        }
    }

    /**
     * The app keeps our stdin open. When the app exits, even by crashing, the kernel closes that pipe and read()
     * returns -1 (end of input). That's the signal to stop everything, so no service outlives the app.
     */
    private static void watchStdin() {
        Thread.ofPlatform().name("stdin-lifeline").start(() -> {
            try {
                while (System.in.read() != -1) {
                    // ignore anything the app writes; only the end of input matters
                }
            } catch (IOException e) {
                // a broken pipe means the same: the app is gone
            }
            System.exit(0); // runs the shutdown hook above
        });
    }
}
