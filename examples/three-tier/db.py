# A fake database: boots for 2 seconds (like Postgres would), then accepts TCP connections on 5433.
import socket, time
print("db: booting...", flush=True)
time.sleep(2)
server = socket.create_server(("127.0.0.1", 5433))
print("db: ready to accept connections on 5433", flush=True)
while True:
    conn, _ = server.accept()
    conn.close()
