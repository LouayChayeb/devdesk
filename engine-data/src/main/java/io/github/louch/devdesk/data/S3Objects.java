package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.ObjectStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * {@link ObjectStore} over the S3 HTTP API, as MinIO and RustFS speak it: plain HTTP requests, signed with
 * {@link SigV4}, answered in XML. Addresses look like {@code http://host:port/bucket/key} ("path style").
 */
public final class S3Objects implements ObjectStore {

    /** MinIO and RustFS accept any region; this is their default and AWS's oldest. */
    private static final String REGION = "us-east-1";

    private static final int PAGE = 500;

    private final HttpClient http = HttpClient.newBuilder()
            // HTTP/1.1: by default Java first asks a plain-HTTP server to switch to HTTP/2 ("h2c"), which some
            // (RabbitMQ's) mishandle on requests with a body.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final Clock clock;

    public S3Objects(Clock clock) {
        this.clock = clock;
    }

    @Override
    public List<Bucket> buckets(DatabaseTarget t) {
        Document xml = xml(send(t, "GET", "/", Map.of(), new byte[0], Map.of()));
        List<Bucket> out = new ArrayList<>();
        NodeList buckets = xml.getElementsByTagName("Bucket");
        for (int i = 0; i < buckets.getLength(); i++) {
            Element b = (Element) buckets.item(i);
            out.add(new Bucket(text(b, "Name"), Instant.parse(text(b, "CreationDate"))));
        }
        return out;
    }

    @Override
    public Listing list(DatabaseTarget t, String bucket, String prefix, Optional<String> after) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("list-type", "2"); // ListObjectsV2
        q.put("prefix", prefix);
        q.put("delimiter", "/"); // keys with a "/" after the prefix come back once, as a folder
        q.put("max-keys", Integer.toString(PAGE));
        after.ifPresent(a -> q.put("continuation-token", a));
        Document xml = xml(send(t, "GET", "/" + SigV4.encode(bucket), q, new byte[0], Map.of()));
        List<String> folders = new ArrayList<>();
        NodeList prefixes = xml.getElementsByTagName("CommonPrefixes");
        for (int i = 0; i < prefixes.getLength(); i++) {
            folders.add(text((Element) prefixes.item(i), "Prefix"));
        }
        List<Item> files = new ArrayList<>();
        NodeList contents = xml.getElementsByTagName("Contents");
        for (int i = 0; i < contents.getLength(); i++) {
            Element c = (Element) contents.item(i);
            files.add(
                    new Item(text(c, "Key"), Long.parseLong(text(c, "Size")), Instant.parse(text(c, "LastModified"))));
        }
        String next = text(xml.getDocumentElement(), "NextContinuationToken");
        return new Listing(folders, files, next.isEmpty() ? Optional.empty() : Optional.of(next));
    }

    @Override
    public Content read(DatabaseTarget t, String bucket, String key, int maxBytes) {
        // A Range header asks for the start only: a 2 GB video isn't downloaded to show its first lines.
        HttpResponse<byte[]> r =
                send(t, "GET", path(bucket, key), Map.of(), new byte[0], Map.of("range", "bytes=0-" + (maxBytes - 1)));
        long size = r.headers()
                .firstValue("content-range")
                .map(v -> Long.parseLong(v.substring(v.lastIndexOf('/') + 1)))
                .orElse((long) r.body().length);
        String type = r.headers().firstValue("content-type").orElse("application/octet-stream");
        return new Content(type, size, r.body(), size > r.body().length);
    }

    @Override
    public void write(DatabaseTarget t, String bucket, String key, byte[] content, String contentType) {
        send(t, "PUT", path(bucket, key), Map.of(), content, Map.of("content-type", contentType));
    }

    @Override
    public void delete(DatabaseTarget t, String bucket, String key) {
        send(t, "DELETE", path(bucket, key), Map.of(), new byte[0], Map.of());
    }

    @Override
    public void createBucket(DatabaseTarget t, String bucket) {
        send(t, "PUT", "/" + SigV4.encode(bucket), Map.of(), new byte[0], Map.of());
    }

    private static String path(String bucket, String key) {
        return "/" + SigV4.encode(bucket) + "/" + SigV4.encodePath(key);
    }

    /** Signs and sends one request; an S3 error answer becomes its own message plus DevDesk's hint. */
    private HttpResponse<byte[]> send(
            DatabaseTarget t,
            String method,
            String path,
            Map<String, String> query,
            byte[] body,
            Map<String, String> extra) {
        String host = t.host() + ":" + t.port();
        Map<String, String> toSign = new LinkedHashMap<>(extra);
        toSign.put("host", host);
        Map<String, String> auth = SigV4.sign(
                method,
                path,
                query,
                toSign,
                SigV4.sha256Hex(body),
                t.user(),
                t.password(),
                REGION,
                "s3",
                clock.instant());
        String q = query.entrySet().stream()
                .map(e -> SigV4.encode(e.getKey()) + "=" + SigV4.encode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder b = HttpRequest.newBuilder(
                        URI.create("http://" + host + path + (q.isEmpty() ? "" : "?" + q)))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        extra.forEach(b::header);
        auth.forEach(b::header);
        try {
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() >= 300) {
                throw problem(t, r);
            }
            return r;
        } catch (ConnectException e) {
            throw new DataException(
                    "Can't reach " + t.service() + " at " + host + ". Is it running? Start it in" + " Services.", e);
        } catch (IOException e) {
            throw new DataException(t.service() + " didn't answer: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataException("interrupted", e);
        }
    }

    private static DataException problem(DatabaseTarget t, HttpResponse<byte[]> r) {
        String code = "";
        String message = "HTTP " + r.statusCode();
        try {
            Document xml = xml(r);
            code = text(xml.getDocumentElement(), "Code");
            message = text(xml.getDocumentElement(), "Message");
        } catch (RuntimeException e) {
            // not XML (a HEAD answer, a proxy page): the status says enough
        }
        String hint = switch (code) {
            case "InvalidAccessKeyId", "SignatureDoesNotMatch", "AccessDenied" ->
                " DevDesk used the keys from " + t.service() + "'s environment in devdesk.yaml (MINIO_ROOT_USER and"
                        + " MINIO_ROOT_PASSWORD, or RUSTFS_ACCESS_KEY and RUSTFS_SECRET_KEY), or the image's default"
                        + " when none is set.";
            case "NoSuchBucket" -> " The bucket doesn't exist (any more).";
            case "NoSuchKey" -> " The file doesn't exist (any more).";
            case "BucketAlreadyOwnedByYou", "BucketAlreadyExists" -> " Pick another name.";
            default -> "";
        };
        return new DataException(message + (code.isEmpty() ? "" : " (" + code + ")") + "." + hint);
    }

    private static Document xml(HttpResponse<byte[]> r) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            // An answer never needs outside files or DTDs: refusing them closes the XML "external entity" hole.
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(r.body()));
        } catch (Exception e) {
            throw new DataException("the S3 answer wasn't XML: "
                    + new String(r.body(), 0, Math.min(200, r.body().length), StandardCharsets.UTF_8));
        }
    }

    /** The text of the first child element called {@code name} (only direct children), or "". */
    private static String text(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e && e.getTagName().equals(name)) {
                return e.getTextContent();
            }
        }
        return "";
    }
}
