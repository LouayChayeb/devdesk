package io.github.louch.devdesk.data;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.DocumentBrowser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonParseException;
import org.bson.json.JsonWriterSettings;

/**
 * {@link DocumentBrowser} with the official MongoDB driver. Like the SQL browsers, each call opens a short connection
 * and closes it: simple, and a local server answers in milliseconds.
 */
public final class MongoBrowser implements DocumentBrowser {

    /** MongoDB's own databases: not the app's data. */
    static final Set<String> SYSTEM = Set.of("admin", "config", "local");
    /** A query typed in the app may run this long. */
    static final Duration QUERY_LIMIT = Duration.ofSeconds(15);

    private static final JsonWriterSettings ONE_LINE =
            JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();
    private static final JsonWriterSettings RELAXED = JsonWriterSettings.builder()
            .outputMode(JsonMode.RELAXED)
            .indent(true)
            .build();

    @Override
    public List<Database> databases(DatabaseTarget t) {
        return with(t, c -> {
            List<Database> out = new ArrayList<>();
            for (Document d : c.listDatabases()) {
                String name = d.getString("name");
                if (!SYSTEM.contains(name)) {
                    out.add(new Database(name, ((Number) d.getOrDefault("sizeOnDisk", 0)).longValue()));
                }
            }
            return out;
        });
    }

    @Override
    public List<Collection> collections(DatabaseTarget t, String database) {
        return with(t, c -> {
            List<Collection> out = new ArrayList<>();
            for (String name : c.getDatabase(database).listCollectionNames()) {
                if (!name.startsWith("system.")) {
                    out.add(new Collection(
                            name, c.getDatabase(database).getCollection(name).estimatedDocumentCount()));
                }
            }
            out.sort((a, b) -> a.name().compareTo(b.name()));
            return out;
        });
    }

    @Override
    public Page find(
            DatabaseTarget t, String database, String collection, String filter, String sort, int skip, int limit) {
        BsonDocument f = json(filter, "filter");
        BsonDocument s = json(sort, "sort");
        return with(t, c -> {
            long started = System.nanoTime();
            MongoCollection<BsonDocument> coll = c.getDatabase(database).getCollection(collection, BsonDocument.class);
            List<String> docs = new ArrayList<>();
            for (BsonDocument d : coll.find(f)
                    .sort(s)
                    .skip(skip)
                    .limit(limit)
                    .maxTime(QUERY_LIMIT.toMillis(), TimeUnit.MILLISECONDS)) {
                docs.add(d.toJson(RELAXED));
            }
            long matching = f.isEmpty() ? coll.estimatedDocumentCount() : coll.countDocuments(f);
            return new Page(docs, matching, Duration.ofNanos(System.nanoTime() - started));
        });
    }

    @Override
    public String insert(DatabaseTarget t, String database, String collection, String document) {
        BsonDocument d = json(document, "document");
        return with(t, c -> {
            BsonValue id = c.getDatabase(database)
                    .getCollection(collection, BsonDocument.class)
                    .insertOne(d)
                    .getInsertedId();
            return id == null ? "" : new BsonDocument("_id", id).toJson(ONE_LINE);
        });
    }

    @Override
    public void replace(DatabaseTarget t, String database, String collection, String id, String document) {
        BsonValue key = id(id);
        BsonDocument d = json(document, "document");
        if (d.containsKey("_id") && !d.get("_id").equals(key)) {
            throw new DataException("the _id can't be changed: delete the document and insert a new one instead");
        }
        with(t, c -> {
            var r = c.getDatabase(database)
                    .getCollection(collection, BsonDocument.class)
                    .replaceOne(Filters.eq("_id", key), d);
            if (r.getMatchedCount() == 0) {
                throw new DataException("no document has that _id any more (deleted meanwhile?): reload the page");
            }
            return null;
        });
    }

    @Override
    public boolean delete(DatabaseTarget t, String database, String collection, String id) {
        BsonValue key = id(id);
        return with(
                t,
                c -> c.getDatabase(database)
                                .getCollection(collection, BsonDocument.class)
                                .deleteOne(Filters.eq("_id", key))
                                .getDeletedCount()
                        > 0);
    }

    /** {"_id": {"$oid": "..."}} or a bare JSON value ({"$oid": "..."}, 7, "abc") → the _id value. */
    private static BsonValue id(String json) {
        BsonValue v = json("{\"_id\": " + json + "}", "_id").get("_id");
        // Given as {"_id": ...} already (what insert returns): take what's inside.
        if (v.isDocument() && v.asDocument().size() == 1 && v.asDocument().containsKey("_id")) {
            return v.asDocument().get("_id");
        }
        return v;
    }

    private static BsonDocument json(String text, String what) {
        if (text == null || text.isBlank()) {
            return new BsonDocument();
        }
        try {
            return BsonDocument.parse(text);
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new DataException("the " + what + " isn't valid JSON: " + e.getMessage()
                    + (what.equals("filter") ? " (example: {\"status\": \"paid\", \"total\": {\"$gt\": 40}})" : ""));
        }
    }

    /** Opens a client with short time limits, runs {@code call}, closes it; errors become plain-words messages. */
    private static <T> T with(DatabaseTarget t, Function<MongoClient, T> call) {
        MongoClientSettings.Builder s = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://" + t.host() + ":" + t.port()))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(3, TimeUnit.SECONDS))
                .applyToSocketSettings(
                        b -> b.connectTimeout(3, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS));
        if (!t.user().isEmpty()) {
            // The official image creates its root user in the "admin" database.
            s.credential(MongoCredential.createCredential(
                    t.user(), "admin", t.password().toCharArray()));
        }
        try (MongoClient c = MongoClients.create(s.build())) {
            return call.apply(c);
        } catch (MongoTimeoutException e) {
            throw new DataException(
                    "Can't reach " + t.service() + " at " + t.host() + ":" + t.port()
                            + ". Is it running? Start it in Services, or see Diagnosis.",
                    e);
        } catch (MongoSecurityException e) {
            throw new DataException(
                    "MongoDB refused the login of " + t.user() + ". DevDesk used "
                            + "MONGO_INITDB_ROOT_USERNAME / MONGO_INITDB_ROOT_PASSWORD from " + t.service()
                            + " in devdesk.yaml"
                            + " (they only apply when the data volume was first created).",
                    e);
        } catch (MongoException e) {
            throw new DataException(e.getMessage(), e);
        }
    }
}
