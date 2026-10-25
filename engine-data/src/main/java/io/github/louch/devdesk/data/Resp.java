package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One connection to Redis speaking RESP2, Redis's wire protocol: a command is an array of byte strings, a reply is
 * one line starting with a type character ({@code + - : $ *}). Small enough to read top to bottom, so no library.
 */
final class Resp implements AutoCloseable {

    /** A reply from Redis, by its first byte on the wire. */
    sealed interface Reply {
        /** {@code +OK} */
        record Simple(String text) implements Reply {}

        /** {@code -ERR unknown command}: Redis said no; the connection is still fine. */
        record Error(String message) implements Reply {}

        /** {@code :42} */
        record Int(long value) implements Reply {}

        /** {@code $5 hello}; {@code bytes} is null for Redis's "nil" ({@code $-1}). */
        record Bulk(byte[] bytes) implements Reply {}

        /** {@code *2 ...}; {@code items} is null for a nil array ({@code *-1}). */
        record Array(List<Reply> items) implements Reply {}
    }

    static final int CONNECT_TIMEOUT_MS = 3000;
    static final int READ_TIMEOUT_MS = 15_000;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final DatabaseTarget target;

    private Resp(Socket socket, DatabaseTarget target) throws IOException {
        this.socket = socket;
        this.target = target;
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    /** Connects and logs in when the service has a password. */
    static Resp connect(DatabaseTarget t) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(t.host(), t.port()), CONNECT_TIMEOUT_MS);
            // A read that waits longer than this gives up, so a stuck server can't hang the app forever.
            socket.setSoTimeout(READ_TIMEOUT_MS);
            Resp r = new Resp(socket, t);
            if (!t.password().isEmpty()) {
                Reply auth = t.user().isEmpty() ? r.call("AUTH", t.password()) : r.call("AUTH", t.user(), t.password());
                if (auth instanceof Reply.Error e) {
                    throw new DataException(e.message() + ". DevDesk used the password from " + t.service()
                            + "'s \"--requirepass\" in devdesk.yaml, or its REDIS_PASSWORD variable.");
                }
            }
            return r;
        } catch (ConnectException | SocketTimeoutException e) {
            close(socket);
            throw new DataException(
                    "Can't reach " + t.service() + " at " + t.host() + ":" + t.port() + " (" + e.getMessage()
                            + "). Is it running? Start it in Services, or see Diagnosis.",
                    e);
        } catch (IOException e) {
            close(socket);
            throw new DataException("Talking to " + t.service() + " failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            close(socket); // e.g. the login failed: don't leave the socket open
            throw e;
        }
    }

    /** For a stream (MONITOR, SUBSCRIBE): reads wait as long as it takes; closing the connection ends them. */
    void waitForever() {
        try {
            socket.setSoTimeout(0);
        } catch (IOException e) {
            throw broken(e);
        }
    }

    /** Sends one command and waits for its reply. */
    Reply call(String... words) {
        send(List.of(words));
        return read();
    }

    /**
     * Pipelining: sends every command first, then reads the replies in the same order. One round trip instead of
     * one per command, which matters when asking TYPE and TTL for a thousand keys.
     */
    List<Reply> pipeline(List<List<String>> commands) {
        commands.forEach(this::send);
        List<Reply> replies = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            replies.add(read());
        }
        return replies;
    }

    /** {@code *3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nhello\r\n}: lengths first, so values may contain any bytes. */
    void send(List<String> words) {
        sendBytes(words.stream().map(w -> w.getBytes(StandardCharsets.UTF_8)).toList());
    }

    /** Like {@link #send}, for arguments that aren't text (RESTORE's serialized value). */
    void sendBytes(List<byte[]> words) {
        try {
            out.write(("*" + words.size() + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (byte[] b : words) {
                out.write(("$" + b.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(b);
                out.write('\r');
                out.write('\n');
            }
            out.flush();
        } catch (IOException e) {
            throw broken(e);
        }
    }

    Reply read() {
        try {
            return readReply(in);
        } catch (IOException e) {
            throw broken(e);
        }
    }

    /** One reply from the stream. The parsing alone, so it can be tested and fuzzed without a socket. */
    static Reply readReply(InputStream in) throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new EOFException("Redis closed the connection");
        }
        String line = line(in);
        return switch (type) {
            case '+' -> new Reply.Simple(line);
            case '-' -> new Reply.Error(line);
            case ':' -> new Reply.Int(Long.parseLong(line));
            case '$' -> {
                int length = Integer.parseInt(line);
                if (length < 0) {
                    yield new Reply.Bulk(null);
                }
                byte[] bytes = in.readNBytes(length);
                in.readNBytes(2); // the \r\n after the data
                yield new Reply.Bulk(bytes);
            }
            case '*' -> {
                int count = Integer.parseInt(line);
                if (count < 0) {
                    yield new Reply.Array(null);
                }
                List<Reply> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    items.add(readReply(in));
                }
                yield new Reply.Array(items);
            }
            default -> throw new IOException("unexpected reply type '" + (char) type + "' (is this really Redis?)");
        };
    }

    /** One line up to \r\n, without it. */
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != '\r') {
            if (c == -1) {
                throw new EOFException("Redis closed the connection mid-reply");
            }
            b.write(c);
        }
        in.read(); // \n
        return b.toString(StandardCharsets.UTF_8);
    }

    private DataException broken(IOException e) {
        String why = e instanceof SocketTimeoutException
                ? "it didn't answer within " + READ_TIMEOUT_MS / 1000 + " s"
                : e.getMessage();
        return new DataException("Lost the connection to " + target.service() + ": " + why, e);
    }

    /**
     * Bytes as text: UTF-8 when they are valid UTF-8, otherwise each odd byte as {@code \xNN}, the way redis-cli
     * shows binary data.
     */
    static String text(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            StringBuilder s = new StringBuilder();
            for (byte b : bytes) {
                int u = b & 0xff;
                if (u >= 0x20 && u < 0x7f && u != '\\') {
                    s.append((char) u);
                } else {
                    s.append(String.format("\\x%02x", u));
                }
            }
            return s.toString();
        }
    }

    @Override
    public void close() {
        close(socket);
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // closing a socket that's already broken: nothing left to do
        }
    }
}
