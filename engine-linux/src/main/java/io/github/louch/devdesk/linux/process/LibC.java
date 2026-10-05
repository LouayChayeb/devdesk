package io.github.louch.devdesk.linux.process;

import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * Direct calls into the C library (libc) through Java's FFM API, for syscalls Java has no API for.
 *
 * <p>Java's {@code ProcessHandle.destroy()} can only signal one PID. The kernel's {@code kill(2)} can signal a whole
 * process group (negative PID) and reports <em>why</em> it failed through {@code errno}.
 */
final class LibC {

    /** errno: no such process. The target already exited, which is fine when stopping. */
    static final int ESRCH = 3;
    /** errno: not permitted. The PID belongs to another user: never ours, so signalling it would be a bug. */
    static final int EPERM = 1;

    private static final Linker LINKER = Linker.nativeLinker();
    // errno is thread-local in C and can be overwritten by the JVM, so FFM captures it right after the call.
    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO = CALL_STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    @SuppressWarnings("restricted") // native calls are "restricted": enabled with --enable-native-access
    private static final MethodHandle KILL = LINKER.downcallHandle(
            LINKER.defaultLookup().find("kill").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT), // int kill(pid_t pid, int sig)
            Linker.Option.captureCallState("errno"));

    private LibC() {}

    /**
     * Calls {@code kill(pid, signal)}. A negative {@code pid} means "every process in group -pid" (kernel rule).
     *
     * @return 0 on success, otherwise the errno value
     */
    static int kill(long pid, int signal) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            int result = (int) KILL.invokeExact(state, Math.toIntExact(pid), signal);
            return result == 0 ? 0 : (int) ERRNO.get(state, 0L);
        } catch (Throwable t) {
            // invokeExact declares Throwable; a failure here means the FFM binding itself is broken.
            throw new IllegalStateException("Calling libc kill() failed", t);
        }
    }
}
