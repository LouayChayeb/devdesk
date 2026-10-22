package io.github.louch.devdesk.linux.terminal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

/**
 * The libc calls behind a pseudo-terminal (pty), through Java's FFM API: Java itself has no API for terminals.
 *
 * <p>A pty is a pair: the "master" side, which DevDesk holds (like the screen and keyboard), and the "slave" side
 * {@code /dev/pts/N}, which the shell gets as its stdin/stdout/stderr and believes is a real terminal.
 */
final class NativePty {

    // Numbers from the Linux headers. The same on x86-64 and arm64 (both use the "generic" values).
    static final int O_RDWR = 2;
    static final int O_NOCTTY = 0400; // octal: don't let opening the master make it DevDesk's own terminal
    static final int O_NONBLOCK = 04000; // octal
    static final int F_SETFL = 4;
    static final long TIOCSWINSZ = 0x5414; // ioctl: "the window is now R rows × C columns"
    static final short POLLIN = 0x1;
    static final short POLLOUT = 0x4;
    static final int EINTR = 4;
    static final int EIO = 5; // reading the master once no program has the slave open
    static final int EAGAIN = 11; // non-blocking fd: nothing to read / no room to write right now

    private static final Linker LINKER = Linker.nativeLinker();
    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
    private static final Linker.Option ERRNO_OPTION = Linker.Option.captureCallState("errno");

    // struct pollfd { int fd; short events; short revents; } and struct winsize { 4 × unsigned short }
    private static final StructLayout POLLFD = MemoryLayout.structLayout(
            JAVA_INT.withName("fd"), JAVA_SHORT.withName("events"), JAVA_SHORT.withName("revents"));
    private static final StructLayout WINSIZE = MemoryLayout.structLayout(
            JAVA_SHORT.withName("row"), JAVA_SHORT.withName("col"), JAVA_SHORT.withName("x"), JAVA_SHORT.withName("y"));

    private static final MethodHandle POSIX_OPENPT = find("posix_openpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle GRANTPT = find("grantpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle UNLOCKPT = find("unlockpt", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle PTSNAME_R =
            find("ptsname_r", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle READ =
            find("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle WRITE =
            find("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle POLL =
            find("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle CLOSE = find("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    // fcntl and ioctl take "..." (variadic) in C: FFM must be told where the variable part starts.
    private static final MethodHandle FCNTL = find(
            "fcntl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT), Linker.Option.firstVariadicArg(2));
    private static final MethodHandle IOCTL = find(
            "ioctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS), Linker.Option.firstVariadicArg(2));

    private NativePty() {}

    /** A native call failed; {@code errno} says why (see {@code man errno}). */
    static final class NativeException extends Exception {
        private static final long serialVersionUID = 1L;
        final int errno;

        NativeException(String call, int errno) {
            super(call + " failed (errno " + errno + ")");
            this.errno = errno;
        }
    }

    /** Opens a new master and makes its slave usable. Returns the master's file descriptor and the slave's path. */
    static Opened open(int columns, int rows) throws NativeException {
        int fd = call("posix_openpt", POSIX_OPENPT, O_RDWR | O_NOCTTY);
        try {
            call("grantpt", GRANTPT, fd); // the slave belongs to us (it already does on Linux; POSIX asks for it)
            call("unlockpt", UNLOCKPT, fd); // a new slave starts locked so nobody opens it half-ready
            // Non-blocking: reads and writes never wait, so closing never waits behind a stuck read or write.
            call("fcntl", FCNTL, fd, F_SETFL, O_NONBLOCK);
            resize(fd, columns, rows); // before the shell starts, so its first prompt already fits
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment name = arena.allocate(128);
                call("ptsname_r", PTSNAME_R, fd, name, 128L);
                return new Opened(fd, name.getString(0, StandardCharsets.UTF_8));
            }
        } catch (NativeException e) {
            close(fd);
            throw e;
        }
    }

    record Opened(int fd, String slavePath) {}

    static void resize(int fd, int columns, int rows) throws NativeException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment size = arena.allocate(WINSIZE);
            size.set(JAVA_SHORT, 0, (short) rows);
            size.set(JAVA_SHORT, 2, (short) columns);
            // The kernel also sends SIGWINCH to the program in front, so vim or top redraw for the new size.
            call("ioctl", IOCTL, fd, TIOCSWINSZ, size);
        }
    }

    /** Waits up to {@code millis} for {@code events} on {@code fd}. Returns true if the fd is ready. */
    static boolean poll(int fd, short events, int millis) throws NativeException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = arena.allocate(POLLFD);
            p.set(JAVA_INT, 0, fd);
            p.set(JAVA_SHORT, 4, events);
            try {
                return call("poll", POLL, p, 1L, millis) > 0;
            } catch (NativeException e) {
                if (e.errno == EINTR) {
                    return false; // a signal arrived while waiting: just try again
                }
                throw e;
            }
        }
    }

    /** One read. Returns the byte count; throws with EAGAIN when nothing is there, EIO when no program is left. */
    static int read(int fd, byte[] buffer) throws NativeException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment b = arena.allocate(buffer.length);
            long n = callLong("read", READ, fd, b, (long) buffer.length);
            MemorySegment.copy(b, JAVA_BYTE, 0, buffer, 0, (int) n);
            return (int) n;
        }
    }

    /** One write. Returns how many bytes the kernel took (maybe fewer than offered). */
    static int write(int fd, byte[] data, int offset, int length) throws NativeException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment b = arena.allocate(length);
            MemorySegment.copy(data, offset, b, JAVA_BYTE, 0, length);
            return (int) callLong("write", WRITE, fd, b, (long) length);
        }
    }

    static void close(int fd) {
        try {
            call("close", CLOSE, fd);
        } catch (NativeException e) {
            // closing can only fail for a wrong fd; nothing to do about it
        }
    }

    @SuppressWarnings("restricted") // native calls are "restricted": enabled with --enable-native-access
    private static MethodHandle find(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        Linker.Option[] all = new Linker.Option[options.length + 1];
        System.arraycopy(options, 0, all, 0, options.length);
        all[options.length] = ERRNO_OPTION;
        return LINKER.downcallHandle(LINKER.defaultLookup().find(name).orElseThrow(), descriptor, all);
    }

    private static int call(String name, MethodHandle handle, Object... args) throws NativeException {
        return (int) callLong(name, handle, args);
    }

    /** Calls a C function; a result of -1 means failure, and errno (captured right after the call) says why. */
    private static long callLong(String name, MethodHandle handle, Object... args) throws NativeException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            Object[] withState = new Object[args.length + 1];
            withState[0] = state;
            System.arraycopy(args, 0, withState, 1, args.length);
            Object result = handle.invokeWithArguments(withState);
            long value = result instanceof Integer i ? i : (Long) result;
            if (value == -1) {
                throw new NativeException(name, (int) ERRNO.get(state, 0L));
            }
            return value;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            // a failure here means the FFM binding itself is broken, not the terminal
            throw new IllegalStateException("Calling libc " + name + "() failed", t);
        }
    }
}
