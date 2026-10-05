package io.github.louch.devdesk.core.network;

/**
 * One open TCP connection held by a process ("ESTABLISHED" in the kernel's words): from its own address and port to
 * the other end's.
 */
public record Connection(long pid, String localAddress, int localPort, String remoteAddress, int remotePort) {

    /** True when the other end is on this machine (127.x, ::1, or IPv4 loopback mapped into IPv6). */
    public boolean remoteIsLocal() {
        return remoteAddress.startsWith("127.")
                || remoteAddress.equals("::1")
                || remoteAddress.startsWith("::ffff:127.");
    }
}
