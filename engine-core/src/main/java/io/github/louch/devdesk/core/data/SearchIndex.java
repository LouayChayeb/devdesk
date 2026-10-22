package io.github.louch.devdesk.core.data;

import java.util.List;

/**
 * Browses Elasticsearch or OpenSearch for the Workbench: the indices, their field mappings, and searches. Read-only:
 * searching never changes an index. Implemented over their HTTP API in engine-data.
 */
public interface SearchIndex {

    /** Indices without the engine's own hidden ones (names starting with "."). */
    List<Index> indices(DatabaseTarget target);

    /** The index's fields and their types, as the engine's own JSON (pretty-printed). */
    String mapping(DatabaseTarget target, String index);

    /**
     * @param query plain words use the "query string" syntax (status:paid AND total:>100); text starting with "{" is
     *     sent as the engine's own query JSON (the body of _search)
     */
    Hits search(DatabaseTarget target, String index, String query, int from, int size);

    /** @param health green, yellow or red */
    record Index(String name, long documents, long bytes, String health) {}

    /** @param total documents matching; {@code exact} false when the engine stopped counting (10,000+) */
    record Hits(long total, boolean exact, long tookMs, List<Hit> hits) {}

    /** @param source the document, as pretty-printed JSON */
    record Hit(String id, double score, String source) {}
}
