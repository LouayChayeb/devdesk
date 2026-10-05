# A fake database that keeps every connection open until the client closes it (like Postgres does).
import socket, threading
server = socket.create_server(("127.0.0.1", 5434))
print("db: ready on 5434", flush=True)
def hold(conn):
    while conn.recv(1024):
        pass
    conn.close()
while True:
    conn, _ = server.accept()
    threading.Thread(target=hold, args=(conn,), daemon=True).start()
