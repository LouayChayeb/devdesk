package io.github.louch.devdesk.core.process;

/**
 * How a process ended, decoded from its raw exit code into something a person can act on.
 *
 * <p>Shells report "killed by signal N" as exit code {@code 128 + N}, and Java passes that number through.
 */
public sealed interface ExitStatus {

    /** Raw exit code as the OS reported it. */
    int code();

    /** One sentence explaining what the code means. */
    String explanation();

    /** Decodes a raw exit code. */
    static ExitStatus of(int code) {
        return switch (code) {
            case 130 -> new KilledBySignal(code, Signal.INT);
            case 137 -> new KilledBySignal(code, Signal.KILL);
            case 143 -> new KilledBySignal(code, Signal.TERM);
            default -> new Exited(code);
        };
    }

    /** The process ended by itself with this code. */
    record Exited(int code) implements ExitStatus {
        @Override
        public String explanation() {
            return switch (code) {
                case 0 -> "exited normally (code 0)";
                case 126 -> "command found but not executable (code 126): check file permissions (chmod +x)";
                case 127 -> "command not found (code 127): check the spelling and that it's on PATH";
                default -> "exited with error code " + code;
            };
        }
    }

    /** The process was ended by a signal. */
    record KilledBySignal(int code, Signal signal) implements ExitStatus {
        @Override
        public String explanation() {
            return switch (signal) {
                case TERM -> "stopped by SIGTERM (code 143)";
                case KILL -> "force-killed by SIGKILL (code 137): it didn't stop in time, or ran out of memory";
                case INT -> "interrupted by SIGINT / Ctrl+C (code 130)";
            };
        }
    }
}
