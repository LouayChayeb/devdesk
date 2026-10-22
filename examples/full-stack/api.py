# The main API. Keeps a small pool of Redis connections open (see the Connections tab) and checks Postgres on /health.
#   GET  /orders        how many orders so far
#   GET  /orders/<id>   one order. Has a bug on purpose: a non-number id crashes the handler (see the logs)
#   POST /orders        {"item": "...", "qty": 1}; saves it in Postgres, answers its id, queues a job for the worker
#   GET  /api-json      the OpenAPI description, where NestJS publishes it: the API page can import it
import json, os, socket, subprocess, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pg import pg_ping, redis_cmd

PORT, PG, REDIS = int(os.environ["PORT"]), int(os.environ["PG_PORT"]), int(os.environ["REDIS_PORT"])
pool = [socket.create_connection(("127.0.0.1", REDIS)) for _ in range(int(os.environ.get("REDIS_POOL", "4")))]
lock = threading.Lock()
orders = 0

OPENAPI = {
    "openapi": "3.0.0",
    "info": {"title": "Shop API", "version": "1.0"},
    "servers": [{"url": "/"}],
    "paths": {
        "/health": {"get": {"summary": "Health", "tags": ["system"]}},
        "/orders": {
            "get": {"summary": "Count orders", "tags": ["orders"]},
            "post": {"summary": "Create an order", "tags": ["orders"],
                     "requestBody": {"content": {"application/json": {"schema": {"$ref": "#/components/schemas/NewOrder"}}}}},
        },
        "/orders/{id}": {"get": {"summary": "Get one order", "tags": ["orders"],
                                 "parameters": [{"name": "id", "in": "path", "required": True, "schema": {"type": "integer", "example": 1}}]}},
    },
    "components": {"schemas": {"NewOrder": {"type": "object", "properties": {
        "item": {"type": "string", "example": "coffee"}, "qty": {"type": "integer", "example": 2}}}}},
}

def insert_order(qty):
    """Saves the order in Postgres (psql inside its container: no Python driver needed) and returns its new id."""
    sql = f"insert into orders(user_id, status, total) values (1 + floor(random() * 40)::int, 'new', {qty * 2.5}) returning id"
    out = subprocess.run(["docker", "exec", "devdesk-full-stack-postgres", "psql", "-U", "postgres", "-d", "shop", "-tA", "-c", sql],
                         capture_output=True, text=True, timeout=10)
    return int(out.stdout.split()[0]) if out.returncode == 0 and out.stdout.strip() else None

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        global orders
        if self.path == "/health":
            ok = pg_ping(PG)
            with lock:
                ok = ok and redis_cmd(pool[0], "PING") == "+PONG"
            self.reply(200 if ok else 503, {"postgres": pg_ping(PG), "redis": ok})
        elif self.path == "/api-json":
            self.reply(200, OPENAPI)
        elif self.path == "/orders":
            with lock:
                orders += 1
                redis_cmd(pool[orders % len(pool)], "INCR", "orders")
            self.reply(200, {"orders": orders})
        elif self.path.startswith("/orders/"):
            order_id = int(self.path.split("/")[2])  # the bug: "/orders/abc" raises ValueError here
            self.reply(200, {"id": order_id, "item": "coffee", "qty": 2})
        else:
            self.reply(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/orders":
            return self.reply(404, {"error": "not found"})
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        order_id = insert_order(int(body.get("qty", 1)))
        with lock:
            redis_cmd(pool[0], "RPUSH", "jobs:new", json.dumps(body))
        print(f"api: order {order_id} saved and queued {body}", flush=True)
        self.reply(201, {"id": order_id, "queued": True, "order": body})

    def reply(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, fmt, *args):
        if not self.path.startswith("/health"):
            print("api:", self.command, self.path, args[1] if len(args) > 1 else "", flush=True)

print(f"api: listening on {PORT} (postgres {PG}, redis {REDIS}, pool of {len(pool)})", flush=True)
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
