# A tiny API whose /health answers 200 only while the database accepts connections.
import os, socket
from http.server import BaseHTTPRequestHandler, HTTPServer

DB_PORT, PORT = int(os.environ["DB_PORT"]), int(os.environ["API_PORT"])

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        try:
            socket.create_connection(("127.0.0.1", DB_PORT), timeout=1).close()
            self.send_response(200 if self.path == "/health" else 404)
        except OSError:
            self.send_response(503)
        self.end_headers()

    def log_message(self, *args):
        pass

print(f"api: listening on {PORT}, postgres on {DB_PORT}", flush=True)
HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
