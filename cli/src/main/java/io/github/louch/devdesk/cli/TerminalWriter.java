package io.github.louch.devdesk.cli;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes lines to the terminal on its own thread, so a terminal that stops reading never blocks the engine.
 *
 * <p>Why: writing to a terminal blocks once the kernel's terminal buffer (a few KB) is full and nobody reads it: the
 * window froze, Ctrl+S was pressed, an SSH connection stalled, or the output goes into {@code less}. Printing from the
 * supervisor's actor thread then froze the whole project, Ctrl+C included. Now the engine only puts lines in a queue;
 * when the queue is full, lines are counted and skipped (the history file still has every state change).
 */
final class TerminalWriter {

    static final int CAPACITY = 10_000;

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong queued = new AtomicLong();
    private final PrintStream stream;

    TerminalWriter(PrintStream terminal) {
        // A daemon thread: if the terminal never reads again, this thread may stay stuck in write(), but it can't keep
        // DevDesk from exiting.
        Thread.ofPlatform().daemon().name("terminal").start(() -> writeLoop(terminal));
        this.stream = new PrintStream(new LineSplitter(), true, StandardCharsets.UTF_8);
    }

    /** Use this instead of the terminal: same API, never blocks. */
    PrintStream stream() {
        return stream;
    }

    /**
     * Never blocks. When the queue is full the line is only counted; a notice goes into the queue at that spot as soon
     * as there is room again, so the gap shows where it happened. Synchronized only to keep notice and line in order.
     */
    synchronized void line(String text) {
        if (dropped.get() > 0 && queue.remainingCapacity() >= 2) {
            enqueue(notice(dropped.getAndSet(0)));
        }
        if (!enqueue(text)) {
            dropped.incrementAndGet();
        }
    }

    private boolean enqueue(String text) {
        boolean ok = queue.offer(text);
        if (ok) {
            queued.incrementAndGet();
        }
        return ok;
    }

    private static String notice(long skipped) {
        return "… " + skipped + " lines not shown: the terminal wasn't reading (see devdesk history)";
    }

    /** At exit: waits up to {@code max} for queued lines to reach the terminal, so the last "■ stopped" lines show. */
    void flush(Duration max) {
        long deadline = System.nanoTime() + max.toNanos();
        synchronized (this) {
            if (dropped.get() > 0 && enqueue(notice(dropped.get()))) {
                dropped.set(0);
            }
        }
        while (written.get() < queued.get() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void writeLoop(PrintStream terminal) {
        try {
            while (true) {
                terminal.println(queue.take());
                written.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Collects bytes until a newline, then queues the line. PrintStream already makes each println atomic. */
    private final class LineSplitter extends OutputStream {
        private final ByteArrayOutputStream current = new ByteArrayOutputStream();

        @Override
        public void write(int b) {
            if (b == '\n') {
                line(current.toString(StandardCharsets.UTF_8));
                current.reset();
            } else {
                current.write(b);
            }
        }
    }
}
