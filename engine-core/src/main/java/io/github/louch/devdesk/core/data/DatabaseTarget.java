package io.github.louch.devdesk.core.data;

/**
 * How to reach one database of the project, worked out from its service (image, published port, environment).
 *
 * @param service the devdesk.yaml service it belongs to ("postgres")
 * @param password from the service's own environment; never printed (see {@link #toString()})
 */
public record DatabaseTarget(
        String service, Kind kind, String host, int port, String user, String password, String database) {

    /** Which database engine it is. */
    public enum Kind {
        POSTGRES,
        /** MySQL and MariaDB (and Percona): one wire protocol. {@code database} is the default schema, may be empty. */
        MYSQL,
        /** A SQLite file on disk: {@code database} is its absolute path; no host, port, user or password. */
        SQLITE,
        /** Redis and its forks (Valkey, KeyDB). {@code user} is empty unless ACL users are set; {@code database} is "0". */
        REDIS,
        /** MongoDB: databases of collections of JSON-like documents. {@code database} is the default one, may be empty. */
        MONGO,
        /** S3-style file storage (MinIO, RustFS): buckets of files. {@code user}/{@code password}: access and secret key. */
        OBJECTS,
        /** Elasticsearch or OpenSearch: indices of JSON documents, searched by text. */
        SEARCH;

        /** Tables and SQL. */
        public boolean isSql() {
            return this == POSTGRES || this == MYSQL || this == SQLITE;
        }

        /** For messages: "Postgres", "MySQL", "SQLite", "Redis". */
        public String label() {
            return switch (this) {
                case POSTGRES -> "Postgres";
                case MYSQL -> "MySQL";
                case SQLITE -> "SQLite";
                case REDIS -> "Redis";
                case MONGO -> "MongoDB";
                case OBJECTS -> "S3 storage";
                case SEARCH -> "search engine";
            };
        }
    }

    /** Principle 7: a target can end up in logs or error messages, so its password never does. */
    @Override
    public String toString() {
        if (kind == Kind.SQLITE) {
            return "SQLITE " + database + " (" + service + ")";
        }
        return kind + " " + user + "@" + host + ":" + port + "/" + database + " (service " + service + ")";
    }
}
