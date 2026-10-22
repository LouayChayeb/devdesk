package io.github.louch.devdesk.data;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS "Signature Version 4": how an S3 server (MinIO, RustFS, AWS itself) knows a request comes from someone holding
 * the secret key. The request's important parts are written in a fixed form, hashed, and signed with a key derived
 * from the secret, the day, the region and the service. The secret itself is never sent.
 */
final class SigV4 {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    static final String EMPTY_HASH = sha256Hex(new byte[0]);

    private SigV4() {}

    /**
     * The headers to add to a request: {@code x-amz-date}, {@code x-amz-content-sha256} and {@code Authorization}.
     *
     * @param path the URL path, already encoded (each key part %-encoded, "/" kept)
     * @param query the query parameters, not encoded (encoded here, sorted by name as the rules say)
     * @param headers headers to sign besides the date and hash; must include "host"
     */
    static Map<String, String> sign(
            String method,
            String path,
            Map<String, String> query,
            Map<String, String> headers,
            String payloadHash,
            String accessKey,
            String secretKey,
            String region,
            String service,
            Instant now) {
        String amzDate = STAMP.format(now);
        String day = amzDate.substring(0, 8);
        TreeMap<String, String> signed = new TreeMap<>();
        headers.forEach((k, v) -> signed.put(k.toLowerCase(), v.strip()));
        signed.put("x-amz-date", amzDate);
        if (service.equals("s3")) {
            signed.put("x-amz-content-sha256", payloadHash); // S3 wants the body's hash as a header too
        }
        String canonicalQuery = new TreeMap<>(query)
                .entrySet().stream()
                        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                        .collect(Collectors.joining("&"));
        String canonicalHeaders = signed.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue() + "\n")
                .collect(Collectors.joining());
        String signedHeaders = String.join(";", signed.keySet());
        String canonicalRequest =
                String.join("\n", method, path, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash);
        String scope = day + "/" + region + "/" + service + "/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        // The signing key changes every day and per region and service: a leaked one is worth little.
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, region);
        key = hmac(key, service);
        key = hmac(key, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(key, toSign));
        Map<String, String> out = new TreeMap<>();
        out.put("x-amz-date", amzDate);
        if (service.equals("s3")) {
            out.put("x-amz-content-sha256", payloadHash);
        }
        out.put(
                "Authorization",
                "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                        + ", Signature=" + signature);
        return out;
    }

    /** RFC 3986 encoding as AWS wants it: only A-Z a-z 0-9 - _ . ~ stay as they are. */
    static String encode(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-_.~".indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    /** A key as a URL path: each part encoded, the "/" between parts kept. */
    static String encodePath(String key) {
        return java.util.Arrays.stream(key.split("/", -1)).map(SigV4::encode).collect(Collectors.joining("/"));
    }

    static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("every Java has HmacSHA256", e);
        }
    }
}
