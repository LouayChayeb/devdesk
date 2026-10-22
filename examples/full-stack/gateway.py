# Front door on :18000. /api/* → api, /auth/* → auth, everything else → web.
import os, urllib.request, urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ["PORT"])
ROUTES = [("/api/", "http://127.0.0.1:18080/"), ("/auth/", "http://127.0.0.1:18081/"), ("/", "http://127.0.0.1:18300/")]

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/health":
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"ok")
            return
        prefix, target = next(r for r in ROUTES if self.path.startswith(r[0]))
        try:
            with urllib.request.urlopen(target + self.path[len(prefix):], timeout=3) as r:
                code, body, ctype = r.status, r.read(), r.headers.get("Content-Type", "text/plain")
        except urllib.error.HTTPError as e:
            code, body, ctype = e.code, e.read(), "application/json"
        except OSError as e:
            code, body, ctype = 502, f"gateway: {target} is down ({e})".encode(), "text/plain"
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print("gateway:", self.command, self.path, flush=True)

print(f"gateway: listening on {PORT}", flush=True)
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
