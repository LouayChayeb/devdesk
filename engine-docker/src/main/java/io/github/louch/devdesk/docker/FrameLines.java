package io.github.louch.devdesk.docker;

import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StreamKind;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Rebuilds lines from Docker log frames. Docker sends output in chunks ("frames") that can end mid-line, or even in
 * the middle of a multi-byte UTF-8 character, so bytes are collected per stream and only whole lines are decoded.
 */
final class FrameLines {

    private static final int MAX_LINE_BYTES = 16 * 1024; // same cap as for processes

    private final Consumer<OutputLine> output;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    FrameLines(Consumer<OutputLine> output) {
        this.output = output;
    }

    synchronized void accept(StreamKind stream, byte[] payload) {
        ByteArrayOutputStream line = stream == StreamKind.STDOUT ? stdout : stderr;
        for (byte b : payload) {
            // Splitting on the newline byte is safe in UTF-8: 0x0A never appears inside a multi-byte character.
            if (b == '\n') {
                emit(stream, line);
            } else {
                line.write(b);
                if (line.size() == MAX_LINE_BYTES) {
                    emit(stream, line);
                }
            }
        }
    }

    /** Delivers a last line that had no newline. */
    synchronized void flush() {
        if (stdout.size() > 0) {
            emit(StreamKind.STDOUT, stdout);
        }
        if (stderr.size() > 0) {
            emit(StreamKind.STDERR, stderr);
        }
    }

    private void emit(StreamKind stream, ByteArrayOutputStream line) {
        // Invalid bytes become U+FFFD instead of throwing.
        String text = line.toString(StandardCharsets.UTF_8);
        line.reset();
        output.accept(new OutputLine(stream, text.endsWith("\r") ? text.substring(0, text.length() - 1) : text));
    }
}
