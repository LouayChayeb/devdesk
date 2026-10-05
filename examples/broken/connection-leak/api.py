# The bug: a new database connection for every "request", and nobody ever closes it.
import socket, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
leaked = []
def handle_requests():
    while True:
        leaked.append(socket.create_connection(("127.0.0.1", 5434)))  # should be closed after use
        time.sleep(2)
class Health(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.end_headers()
    def log_message(self, *args):
        pass
threading.Thread(target=handle_requests, daemon=True).start()
print("api: listening on 3009", flush=True)
ThreadingHTTPServer(("127.0.0.1", 3009), Health).serve_forever()
