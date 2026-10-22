package io.github.louch.devdesk.core.data;

import java.time.Duration;
import java.util.List;

/**
 * Reads and changes a document database (MongoDB) for the Workbench: databases hold collections, collections hold
 * documents. Documents travel as JSON text (MongoDB's "relaxed Extended JSON": ObjectIds as {"$oid": "..."}, dates
 * as {"$date": "..."}), so nothing about their types is lost when one is edited and saved back.
 */
public interface DocumentBrowser {

    /** Databases with their size on disk; MongoDB's own (admin, config, local) are left out. */
    List<Database> databases(DatabaseTarget target);

    /** Collections of one database, with their document counts (MongoDB's estimate: instant even for big ones). */
    List<Collection> collections(DatabaseTarget target, String database);

    /**
     * One page of documents.
     *
     * @param filter a MongoDB query as JSON ({"status": "paid", "total": {"$gt": 40}}); blank = all
     * @param sort as JSON ({"createdAt": -1}); blank = the collection's natural order
     */
    Page find(
            DatabaseTarget target, String database, String collection, String filter, String sort, int skip, int limit);

    /** @return the new document's _id, as JSON */
    String insert(DatabaseTarget target, String database, String collection, String document);

    /**
     * Replaces the document with this _id by {@code document} (whose own _id must be the same or absent).
     *
     * @throws DataException when no document has that _id any more
     */
    void replace(DatabaseTarget target, String database, String collection, String id, String document);

    /** @return false when no document had that _id */
    boolean delete(DatabaseTarget target, String database, String collection, String id);

    /** A database and its size on disk. */
    record Database(String name, long sizeBytes) {}

    /** A collection and how many documents it holds. */
    record Collection(String name, long documents) {}

    /** @param matching documents that match the filter in total (beyond this page) */
    record Page(List<String> documents, long matching, Duration took) {}
}
