# A tiny API: /health answers 200 only if the database accepts a connection.
import os, socket
from http.server import BaseHTTPRequestHandler, HTTPServer

DB_PORT = int(os.environ["DB_PORT"])

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            socket.create_connection(("127.0.0.1", DB_PORT), timeout=1).close()
            self.send_response(200 if self.path == "/health" else 404)
        except OSError:
            self.send_response(503)  # database unreachable
        self.end_headers()

    def log_message(self, *args):
        pass  # keep the output quiet: health checks call /health every few seconds

print(f"api: listening on 3001, database on {DB_PORT}", flush=True)
HTTPServer(("127.0.0.1", 3001), Handler).serve_forever()
