package io.github.louch.devdesk.linux.process;

import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StreamKind;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Reads one output pipe of a child process until it closes, and hands over each line.
 *
 * <p>It must run all the time: the kernel's pipe buffer is only ~64 KiB, and a child whose buffer is full freezes on
 * its next write until someone reads.
 */
final class LineReader implements Runnable {

    /** Longer lines are split, so one runaway line (e.g. minified JSON) can't eat all memory. */
    static final int MAX_LINE_CHARS = 16 * 1024;

    private final InputStream pipe;
    private final StreamKind stream;
    private final Consumer<OutputLine> output;

    LineReader(InputStream pipe, StreamKind stream, Consumer<OutputLine> output) {
        this.pipe = pipe;
        this.stream = stream;
        this.output = output;
    }

    @Override
    public void run() {
        // InputStreamReader replaces invalid UTF-8 bytes with U+FFFD instead of throwing.
        try (Reader reader = new InputStreamReader(pipe, StandardCharsets.UTF_8)) {
            split(reader);
        } catch (IOException e) {
            // The pipe was closed under us (process killed). Everything readable was already delivered.
        }
    }

    void split(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        char[] buffer = new char[8192];
        int n;
        while ((n = reader.read(buffer)) != -1) {
            for (int i = 0; i < n; i++) {
                char c = buffer[i];
                if (c == '\n') {
                    emit(line);
                } else {
                    line.append(c);
                    if (line.length() == MAX_LINE_CHARS) {
                        emit(line);
                    }
                }
            }
        }
        if (!line.isEmpty()) {
            emit(line); // last line without a trailing newline
        }
    }

    private void emit(StringBuilder line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') {
            end--; // Windows-style "\r\n" line ending
        }
        output.accept(new OutputLine(stream, line.substring(0, end)));
        line.setLength(0);
    }
}
