package io.github.louch.devdesk.linux.terminal;

import io.github.louch.devdesk.core.terminal.Terminals;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Linux implementation of core's {@link Terminals}: each shell runs in its own session on a new pseudo-terminal. */
public final class PtyTerminals implements Terminals {

    /**
     * Runs before the real command, inside the new session that {@code setsid} made. Opening the slave there (without
     * O_NOCTTY) makes it the session's <em>controlling terminal</em>: then Ctrl+C reaches the program as SIGINT and
     * job control (Ctrl+Z, fg) works. If Java opened the slave instead, the terminal would have no session.
     */
    static final String ATTACH = "exec 0<>\"$1\" 1>&0 2>&0; shift; exec \"$@\"";

    /** How long a program may take to exit after its terminal hung up before it's killed. */
    private static final long HANGUP_GRACE_MILLIS = 2000;

    private static final int POLL_MILLIS = 200;

    @Override
    public Terminal open(List<String> command, Path directory, Map<String, String> environment, int columns, int rows)
            throws IOException {
        NativePty.Opened pty;
        try {
            pty = NativePty.open(columns, rows);
        } catch (NativePty.NativeException e) {
            throw new IOException("could not create a terminal: " + e.getMessage(), e);
        }
        List<String> argv = new ArrayList<>(List.of("setsid", "/bin/sh", "-c", ATTACH, "devdesk-terminal"));
        argv.add(pty.slavePath());
        argv.addAll(command);
        ProcessBuilder builder = new ProcessBuilder(argv)
                .directory(directory.toFile())
                // Everything the program reads and writes goes through the terminal; these are never used.
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().clear();
        builder.environment().putAll(environment);
        builder.environment().put("TERM", "xterm-256color"); // what the app's terminal understands (colors, keys)
        try {
            return new PtyTerminal(pty.fd(), builder.start());
        } catch (IOException e) {
            NativePty.close(pty.fd());
            throw new IOException("could not start " + String.join(" ", command) + ": " + e.getMessage(), e);
        }
    }

    /** One shell and its terminal. The master fd is closed once, under the write lock, so no read uses a stale fd. */
    private static final class PtyTerminal implements Terminal {
        private final int fd;
        private final Process process;
        // Reads, writes and resizes share the "read" side; close takes the "write" side, so it waits for them.
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
        private volatile boolean closed;

        PtyTerminal(int fd, Process process) {
            this.fd = fd;
            this.process = process;
        }

        @Override
        public int read(byte[] buffer) throws IOException {
            while (true) {
                lock.readLock().lock();
                try {
                    if (closed) {
                        return -1;
                    }
                    if (NativePty.poll(fd, NativePty.POLLIN, POLL_MILLIS)) {
                        return NativePty.read(fd, buffer);
                    }
                } catch (NativePty.NativeException e) {
                    // EIO: no program has the slave open (not yet, or not anymore). EAGAIN: nothing after all.
                    if (e.errno != NativePty.EIO && e.errno != NativePty.EAGAIN) {
                        throw new IOException("reading the terminal: " + e.getMessage(), e);
                    }
                    if (!process.isAlive()) {
                        return -1; // the program exited and everything it printed was read
                    }
                    pause(); // the shell hasn't opened its side yet: the master reports EIO at once until it does
                } finally {
                    lock.readLock().unlock();
                }
                if (!process.isAlive() && !hasOutput()) {
                    return -1;
                }
            }
        }

        private boolean hasOutput() throws IOException {
            lock.readLock().lock();
            try {
                return !closed && NativePty.poll(fd, NativePty.POLLIN, 0);
            } catch (NativePty.NativeException e) {
                return false;
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public void write(byte[] input) throws IOException {
            int done = 0;
            while (done < input.length) {
                lock.readLock().lock();
                try {
                    if (closed) {
                        throw new IOException("the terminal is closed");
                    }
                    // Wait for room: a program that doesn't read its input fills the kernel's buffer (about 4 KB).
                    if (NativePty.poll(fd, NativePty.POLLOUT, POLL_MILLIS)) {
                        done += NativePty.write(fd, input, done, input.length - done);
                    }
                } catch (NativePty.NativeException e) {
                    if (e.errno != NativePty.EAGAIN) {
                        throw new IOException("writing to the terminal: " + e.getMessage(), e);
                    }
                } finally {
                    lock.readLock().unlock();
                }
            }
        }

        @Override
        public void resize(int columns, int rows) {
            lock.readLock().lock();
            try {
                if (!closed) {
                    NativePty.resize(fd, columns, rows);
                }
            } catch (NativePty.NativeException e) {
                // the shell just exited: no window to resize
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override
        public OptionalInt exitCode() {
            return process.isAlive() ? OptionalInt.empty() : OptionalInt.of(process.exitValue());
        }

        @Override
        public void close() {
            lock.writeLock().lock();
            try {
                if (closed) {
                    return;
                }
                closed = true;
                // Closing the master is a "hangup": the kernel sends SIGHUP to the shell and the program in front,
                // like closing a terminal window. Most programs exit on it.
                NativePty.close(fd);
            } finally {
                lock.writeLock().unlock();
            }
            try {
                if (!process.waitFor(HANGUP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                    // We started this shell, so it and what it started are ours to stop (never anything else).
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        private static void pause() {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
