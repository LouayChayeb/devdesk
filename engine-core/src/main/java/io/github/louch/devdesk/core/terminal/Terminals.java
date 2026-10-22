package io.github.louch.devdesk.core.terminal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The port that runs an interactive program (a shell) in a terminal; the Linux adapter uses a pseudo-terminal. */
public interface Terminals {

    /** Starts {@code command} attached to a new terminal of {@code columns} × {@code rows} characters. */
    Terminal open(List<String> command, Path directory, Map<String, String> environment, int columns, int rows)
            throws IOException;

    /** One running terminal. Only DevDesk's terminal window talks to it, from one reader and one writer thread. */
    interface Terminal extends AutoCloseable {

        /**
         * Waits for what the program prints (bytes as they come: colors, cursor moves).
         *
         * @return how many bytes were put in {@code buffer}; -1 once the program has exited and everything was read
         */
        int read(byte[] buffer) throws IOException;

        /** What the user types. */
        void write(byte[] input) throws IOException;

        /** The window changed size: full-screen programs (vim, top) redraw for it. */
        void resize(int columns, int rows);

        /** The program's exit code once it has exited; empty while it runs. */
        java.util.OptionalInt exitCode();

        /** Ends the program and everything it started, then frees the terminal. Safe to call twice. */
        @Override
        void close();
    }
}
