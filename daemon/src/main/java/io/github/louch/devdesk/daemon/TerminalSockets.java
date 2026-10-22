package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.terminal.Terminals;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.eclipse.jetty.websocket.api.Callback;

/**
 * The app's terminal windows, one WebSocket each. What the shell prints goes to the app as binary messages (raw
 * bytes: colors, cursor moves); the app sends JSON: {@code {"type":"input","data":"ls\r"}} or
 * {@code {"type":"resize","columns":120,"rows":40}}. Closing the window ends the shell.
 */
final class TerminalSockets {

    private final ServiceShells shells;
    private final Map<String, Terminals.Terminal> open = new ConcurrentHashMap<>();

    TerminalSockets(ServiceShells shells) {
        this.shells = shells;
    }

    /** @param allowed checks the token and the page's origin, like the events socket */
    void configure(WsConfig ws, Predicate<WsConnectContext> allowed) {
        ws.onConnect(ctx -> {
            if (!allowed.test(ctx)) {
                ctx.closeSession(4401, "missing or wrong token, or a page that isn't the DevDesk app");
                return;
            }
            String service = String.valueOf(ctx.queryParam("service"));
            switch (shells.open(service, size(ctx.queryParam("columns"), 80), size(ctx.queryParam("rows"), 24))) {
                case ServiceShells.Result.Refused r -> {
                    sendText(ctx, ApiJson.write(Map.of("type", "refused", "message", r.reason())));
                    ctx.closeSession(1000, "refused");
                }
                case ServiceShells.Result.Opened o -> {
                    open.put(ctx.sessionId(), o.terminal());
                    sendText(ctx, ApiJson.write(Map.of("type", "opened", "title", o.title())));
                    // A platform thread: the read is a native call, which would pin a virtual thread anyway.
                    Thread.ofPlatform().daemon().name("terminal-" + service).start(() -> pump(ctx, o.terminal()));
                }
            }
        });
        ws.onMessage(ctx -> {
            Terminals.Terminal t = open.get(ctx.sessionId());
            if (t == null) {
                return;
            }
            Map<?, ?> m = ApiJson.MAPPER.readValue(ctx.message(), Map.class);
            switch (String.valueOf(m.get("type"))) {
                case "input" -> t.write(String.valueOf(m.get("data")).getBytes(StandardCharsets.UTF_8));
                case "resize" -> {
                    if (m.get("columns") instanceof Number c && m.get("rows") instanceof Number r) {
                        t.resize(Math.clamp(c.intValue(), 2, 1000), Math.clamp(r.intValue(), 2, 500));
                    }
                }
                default -> {
                    // unknown message: ignore, an older or newer app
                }
            }
        });
        ws.onClose(ctx -> {
            Terminals.Terminal t = open.remove(ctx.sessionId());
            if (t != null) {
                Thread.ofVirtual().start(t::close); // close waits up to 2 s for the shell: not on Jetty's thread
            }
        });
    }

    /** Copies the shell's output to the window until the shell exits, then says how it ended. */
    private void pump(WsContext ctx, Terminals.Terminal terminal) {
        byte[] buffer = new byte[16 * 1024];
        try {
            int n;
            while ((n = terminal.read(buffer)) != -1) {
                // Waits until it's sent: a program printing nonstop (yes, a big cat) then can't fill the memory.
                ByteBuffer bytes = ByteBuffer.wrap(Arrays.copyOf(buffer, n));
                send(sent -> ctx.session.sendBinary(bytes, sent));
            }
            Map<String, Object> exited = new LinkedHashMap<>();
            exited.put("type", "exited");
            exited.put(
                    "code",
                    terminal.exitCode().isPresent() ? terminal.exitCode().getAsInt() : null);
            sendText(ctx, ApiJson.write(exited));
            ctx.closeSession(1000, "the shell exited");
        } catch (IOException | RuntimeException | java.util.concurrent.ExecutionException e) {
            // the window closed while sending: onClose ends the shell
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (open.remove(ctx.sessionId()) != null) {
                terminal.close();
            }
        }
    }

    private static void sendText(WsContext ctx, String text) {
        try {
            send(sent -> ctx.session.sendText(text, sent));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            // the window is already gone
        }
    }

    /**
     * One message at a time, each waited for: Jetty refuses a new message while the previous one is still being
     * written, and drops the connection.
     */
    private static void send(java.util.function.Consumer<Callback> message)
            throws InterruptedException, java.util.concurrent.ExecutionException {
        Callback.Completable sent = new Callback.Completable();
        message.accept(sent);
        sent.get();
    }

    void closeAll() {
        open.values().forEach(Terminals.Terminal::close);
        open.clear();
    }

    private static int size(String given, int fallback) {
        try {
            return given == null ? fallback : Math.clamp(Integer.parseInt(given), 2, 1000);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
