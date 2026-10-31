# Login service. Healthy while Postgres answers.
#   POST /login {"user": "ada", "password": "dev"} → {"access_token": "..."} and a "session" cookie
#   GET  /me    with "Authorization: Bearer <token>" or the session cookie → who you are, else 401
import json, os, secrets, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pg import pg_ping

PORT, PG = int(os.environ["PORT"]), int(os.environ["PG_PORT"])
# Break it on purpose: JWT_SECRET= devdesk up  (an empty secret, like a .env someone forgot to fill in)
if not os.environ.get("JWT_SECRET"):
    sys.exit("auth: JWT_SECRET is empty, refusing to start: login tokens can't be signed without it")
tokens = {}  # token → user name

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            ok = pg_ping(PG)
            return self.reply(200 if ok else 503, {"service": "auth", "postgres": ok})
        if self.path == "/me":
            user = self.user()
            return self.reply(200, {"user": user}) if user else self.reply(401, {"error": "log in first: POST /login"})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/login":
            return self.reply(404, {"error": "not found"})
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if body.get("password") != "dev":
            print(f"auth: failed login for {body.get('user')!r}", flush=True)
            return self.reply(401, {"error": "wrong user or password (the password is dev)"})
        token = secrets.token_hex(16)
        tokens[token] = body.get("user", "dev")
        print(f"auth: {tokens[token]} logged in", flush=True)
        self.reply(200, {"access_token": token, "user": tokens[token]}, cookie=f"session={token}; Path=/; HttpOnly")

    def user(self):
        auth = self.headers.get("Authorization", "")
        cookie = self.headers.get("Cookie", "")
        token = auth[7:] if auth.startswith("Bearer ") else next(
            (c.split("=", 1)[1] for c in cookie.split("; ") if c.startswith("session=")), "")
        return tokens.get(token)

    def reply(self, code, body, cookie=None):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass

print(f"auth: listening on {PORT}", flush=True)
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
