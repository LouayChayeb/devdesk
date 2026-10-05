package io.github.louch.devdesk.daemon;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.json.JsonMapper;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The engine's local HTTP + WebSocket API for the desktop app. Listens on 127.0.0.1 only.
 *
 * <p>Three locks on the door, because any website open in your browser can also send requests to 127.0.0.1:
 * <ol>
 *   <li><b>Token</b>: a random secret only the app knows; every request must carry it.
 *   <li><b>Host check</b>: the Host header must be 127.0.0.1 or localhost, which defeats "DNS rebinding" (a website
 *       making its own domain point at 127.0.0.1).
 *   <li><b>Origin check</b>: browsers say which page a request comes from; only the app's own pages are allowed.
 * </ol>
 */
final class ApiServer {

    /** The desktop app's page origins (Tauri on Linux, and the dev servers while developing the UI). */
    static final Set<String> ALLOWED_ORIGINS = Set.of(
            "tauri://localhost",
            "http://tauri.localhost",
            "https://tauri.localhost",
            "http://localhost:1420",
            "http://127.0.0.1:1420",
            "http://localhost:4200",
            "http://127.0.0.1:4200");

    private final EngineSession session;
    private final byte[] token;
    private final Map<String, EventClient> clients = new ConcurrentHashMap<>();
    private Javalin app;

    ApiServer(EngineSession session, String token) {
        this.session = session;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /** Starts on 127.0.0.1 and a free port chosen by the kernel (port 0). Returns that port. */
    int start() {
        app = Javalin.create(config -> {
            config.jsonMapper(new Jackson3Mapper());
            config.startup.showJavalinBanner = false;
            config.routes.before(this::guard);
            config.routes.options("/*", ctx -> ctx.status(HttpStatus.NO_CONTENT));
            config.routes.get("/api/v1/project", ctx -> answer(ctx, session.project()));
            config.routes.post("/api/v1/project/open", ctx -> {
                Map<?, ?> body = ctx.bodyAsClass(Map.class);
                Object path = body.get("path");
                if (!(path instanceof String p) || p.isBlank()) {
                    ctx.status(400).json(ApiJson.error("send {\"path\": \"/folder/with/devdesk.yaml\"}"));
                    return;
                }
                answer(ctx, session.open(Path.of(p.replaceFirst("^~", System.getProperty("user.home")))));
            });
            config.routes.post("/api/v1/project/trust", ctx -> answer(ctx, session.trust()));
            config.routes.post("/api/v1/project/up", ctx -> answer(ctx, session.up()));
            config.routes.post("/api/v1/project/down", ctx -> answer(ctx, session.down()));
            config.routes.get("/api/v1/project/why", ctx -> answer(ctx, session.why()));
            config.routes.get("/api/v1/project/history", ctx -> {
                // ?hours=24 by default; at most the 14 days the recorder keeps.
                String hours = ctx.queryParam("hours");
                if (hours != null && !hours.matches("\\d{1,3}")) {
                    ctx.status(400).json(ApiJson.error("hours must be a whole number, like ?hours=24"));
                    return;
                }
                int h = hours == null ? 24 : Math.min(Integer.parseInt(hours), 14 * 24);
                answer(ctx, session.history(Duration.ofHours(h)));
            });
            config.routes.get("/api/v1/project/machine", ctx -> answer(ctx, session.machine()));
            config.routes.ws("/api/v1/events", ws -> {
                ws.onConnect(ctx -> {
                    // Browsers can't add headers to a WebSocket, so the token comes as ?token=...
                    if (!tokenMatches(ctx.queryParam("token")) || !originAllowed(ctx.header("Origin"))) {
                        ctx.closeSession(4401, "missing or wrong token, or a page that isn't the DevDesk app");
                        return;
                    }
                    EventClient client = new EventClient(
                            ctx.sessionId(),
                            ctx::send,
                            () -> ctx.closeSession(4408, "too far behind: reconnect to resync"));
                    clients.put(ctx.sessionId(), client);
                    session.connect(client);
                });
                ws.onClose(ctx -> {
                    EventClient c = clients.remove(ctx.sessionId());
                    if (c != null) {
                        session.disconnect(c);
                    }
                });
            });
            config.routes.exception(
                    Exception.class,
                    (e, ctx) -> ctx.status(500)
                            .json(ApiJson.error("internal error (a DevDesk bug, please report it): " + e)));
        });
        app.start("127.0.0.1", 0);
        return app.port();
    }

    void stop() {
        if (app != null) {
            app.stop();
        }
    }

    /** Runs before every request: CORS headers for the app, then the three checks. */
    private void guard(Context ctx) {
        String origin = ctx.header("Origin");
        if (originAllowed(origin) && origin != null) {
            ctx.header("Access-Control-Allow-Origin", origin);
            ctx.header("Access-Control-Allow-Headers", "Authorization, Content-Type");
            ctx.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ctx.header("Vary", "Origin");
        }
        if (!hostAllowed(ctx.host())) {
            ctx.status(403).json(ApiJson.error("wrong Host header: DevDesk only answers on 127.0.0.1"));
            ctx.skipRemainingHandlers();
            return;
        }
        if (!originAllowed(origin)) {
            ctx.status(403).json(ApiJson.error("requests from " + origin + " are not allowed"));
            ctx.skipRemainingHandlers();
            return;
        }
        boolean preflight = ctx.method().name().equals("OPTIONS"); // browsers send these without the token
        boolean events = ctx.path().equals("/api/v1/events"); // checked in onConnect
        String auth = ctx.header("Authorization");
        if (!preflight && !events && !tokenMatches(auth == null ? null : auth.replaceFirst("^Bearer ", ""))) {
            ctx.status(401).json(ApiJson.error("missing or wrong token"));
            ctx.skipRemainingHandlers();
        }
    }

    /** Constant-time comparison, so the response time doesn't hint at how much of a guess was right. */
    private boolean tokenMatches(String given) {
        return given != null && MessageDigest.isEqual(token, given.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean hostAllowed(String host) {
        if (host == null) {
            return false;
        }
        String name = host.replaceFirst(":\\d+$", "");
        return name.equals("127.0.0.1") || name.equals("localhost");
    }

    /** No Origin at all = not a browser page (e.g. curl or the Tauri Rust side): allowed, the token still applies. */
    private static boolean originAllowed(String origin) {
        return origin == null || ALLOWED_ORIGINS.contains(origin);
    }

    private static void answer(Context ctx, EngineSession.Answer answer) {
        ctx.status(answer.status()).json(answer.body());
    }

    /** Lets Javalin read and write JSON with Jackson 3 (Javalin's built-in mapper uses Jackson 2). */
    private static final class Jackson3Mapper implements JsonMapper {
        @Override
        public String toJsonString(Object obj, Type type) {
            return ApiJson.MAPPER.writeValueAsString(obj);
        }

        @Override
        public <T> T fromJsonString(String json, Type targetType) {
            return ApiJson.MAPPER.readValue(json, ApiJson.MAPPER.constructType(targetType));
        }
    }
}
