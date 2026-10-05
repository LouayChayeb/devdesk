package io.github.louch.devdesk.daemon;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.function.Consumer;

/**
 * One connected app window. Events go into a bounded queue and a virtual thread sends them, so a slow or stuck
 * window never slows down the engine. If its queue fills up, it's disconnected and simply resyncs on reconnect.
 */
final class EventClient {

    private static final int MAX_QUEUED = 10_000;

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(MAX_QUEUED);
    private final Consumer<String> send;
    private final Runnable disconnect;
    private final Thread sender;
    private volatile boolean closed;

    EventClient(String name, Consumer<String> send, Runnable disconnect) {
        this.send = send;
        this.disconnect = disconnect;
        this.sender = Thread.ofVirtual().name("events-" + name).start(this::drain);
    }

    /** Never blocks. Returns false if the client fell too far behind and was dropped. */
    boolean offer(String json) {
        if (closed) {
            return false;
        }
        if (!queue.offer(json)) {
            close();
            disconnect.run();
            return false;
        }
        return true;
    }

    void close() {
        closed = true;
        sender.interrupt();
    }

    private void drain() {
        try {
            while (!closed) {
                send.accept(queue.take());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            closed = true; // the window went away mid-send
        }
    }
}
