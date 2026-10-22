package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.http.HttpCall;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A multipart/form-data body (RFC 7578), what a browser sends for a form with a file input: each field is a "part"
 * with its own small header, between lines holding the boundary.
 */
final class Multipart {

    /** Uploads bigger than this belong in a real client; it keeps the engine's memory small. */
    static final long MAX_FILE = 100L * 1024 * 1024;

    private Multipart() {}

    /** @throws UncheckedIOException when a file can't be read (the message names it) */
    static byte[] body(List<HttpCall.Field> fields, String boundary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (HttpCall.Field f : fields) {
                write(out, "--" + boundary + "\r\n");
                if (f.file()) {
                    Path file = Path.of(f.value());
                    if (!Files.isRegularFile(file)) {
                        throw new IOException("the file to upload isn't there: " + file);
                    }
                    if (Files.size(file) > MAX_FILE) {
                        throw new IOException(file + " is bigger than " + MAX_FILE / 1024 / 1024 + " MB");
                    }
                    String type = Files.probeContentType(file);
                    write(
                            out,
                            "Content-Disposition: form-data; name=\"" + escape(f.name()) + "\"; filename=\""
                                    + escape(file.getFileName().toString()) + "\"\r\n");
                    write(out, "Content-Type: " + (type == null ? "application/octet-stream" : type) + "\r\n\r\n");
                    out.write(Files.readAllBytes(file));
                } else {
                    write(out, "Content-Disposition: form-data; name=\"" + escape(f.name()) + "\"\r\n\r\n");
                    write(out, f.value());
                }
                write(out, "\r\n");
            }
            write(out, "--" + boundary + "--\r\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Quotes in a field or file name would end the header value early. */
    private static String escape(String s) {
        return s.replace("\"", "%22").replace("\r", "").replace("\n", "");
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
