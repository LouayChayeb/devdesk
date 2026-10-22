package io.github.louch.devdesk.core.data;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Browses S3-style file storage (MinIO, RustFS) for the Workbench: buckets, folders and files, and the few explicit
 * changes a developer makes by hand (upload, delete, new bucket). Implemented over the S3 HTTP API in engine-data.
 */
public interface ObjectStore {

    /** Buckets, by name. */
    List<Bucket> buckets(DatabaseTarget target);

    /**
     * One folder level: S3 has no folders, only keys like "invoices/2026/a.pdf"; "/" in a key is shown as a folder.
     *
     * @param prefix the folder, e.g. "invoices/2026/" ("" for the top)
     * @param after where the previous page ended (empty for the first page)
     */
    Listing list(DatabaseTarget target, String bucket, String prefix, Optional<String> after);

    /** The start of a file (at most {@code maxBytes}), to preview it. */
    Content read(DatabaseTarget target, String bucket, String key, int maxBytes);

    void write(DatabaseTarget target, String bucket, String key, byte[] content, String contentType);

    void delete(DatabaseTarget target, String bucket, String key);

    void createBucket(DatabaseTarget target, String bucket);

    /** A bucket and when it was made. */
    record Bucket(String name, Instant created) {}

    /** One folder level; {@code next} is where the next page starts, if there is one. */
    record Listing(List<String> folders, List<Item> files, Optional<String> next) {}

    /** One file: its full key, size in bytes and last change. */
    record Item(String key, long size, Instant modified) {}

    /** @param truncated true when the file is bigger than what was read */
    record Content(String contentType, long size, byte[] bytes, boolean truncated) {}
}
