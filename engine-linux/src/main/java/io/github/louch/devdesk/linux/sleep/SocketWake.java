package io.github.louch.devdesk.linux.sleep;

import io.github.louch.devdesk.core.sleep.WakeOnConnect;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

/**
 * {@link WakeOnConnect} with plain sockets. The order matters: the listening socket is closed before the service
 * starts (both can't own the port), while the accepted connection stays open; then DevDesk connects to the service
 * itself and copies bytes both ways until either side closes.
 */
public final class SocketWake implements WakeOnConnect {

    @Override
    public Waiting listen(int port, Runnable wake, Duration maxWait) {
        ServerSocket server;
        try {
            server = new ServerSocket();
            // Only for this machine, like the services' ports normally are reached from here.
            server.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException e) {
            throw new IllegalStateException("port " + port + " is in use, so a connection can't wake the service", e);
        }
        Thread.ofVirtual().name("wake-" + port).start(() -> {
            Socket held;
            try {
                held = server.accept();
            } catch (IOException e) {
                return; // closed: the service was started some other way
            }
            close(server); // free the port for the service
            wake.run();
            Socket service = connectWhenUp(port, maxWait);
            if (service == null) {
                close(held);
                return;
            }
            pipe(held, service);
        });
        return () -> close(server);
    }

    /** Tries to connect every 200 ms until the woken service listens, or gives up after {@code maxWait}. */
    private static Socket connectWhenUp(int port, Duration maxWait) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (System.nanoTime() < deadline) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return s;
            } catch (IOException e) {
                close(s);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /** Copies bytes both ways; when one side closes, both are closed. */
    private static void pipe(Socket a, Socket b) {
        Thread.ofVirtual().start(() -> copy(a, b));
        copy(b, a);
    }

    private static void copy(Socket from, Socket to) {
        try (InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream()) {
            in.transferTo(out);
        } catch (IOException e) {
            // one side went away: close both below
        } finally {
            close(from);
            close(to);
        }
    }

    private static void close(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            // already closed
        }
    }
}
