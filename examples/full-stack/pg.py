# Tiny helpers shared by the services (Python standard library only).
import socket, struct

def pg_ping(port, timeout=1.0):
    """True if a real Postgres answers on this port. Sends Postgres's SSLRequest; a live server replies 'S' or 'N'."""
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout) as s:
            s.sendall(struct.pack("!ii", 8, 80877103))
            return s.recv(1) in (b"S", b"N")
    except OSError:
        return False

def redis_cmd(sock, *args):
    """Sends one Redis command (RESP protocol) on an open socket and returns the first reply line."""
    payload = f"*{len(args)}\r\n" + "".join(f"${len(a)}\r\n{a}\r\n" for a in args)
    sock.sendall(payload.encode())
    return sock.recv(4096).split(b"\r\n")[0].decode()
