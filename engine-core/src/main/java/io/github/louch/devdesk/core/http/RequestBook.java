package io.github.louch.devdesk.core.http;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where the API client keeps a project's saved requests, the user's variables and recent history. On Linux: one
 * JSON file per project under $XDG_STATE_HOME; in tests: memory.
 */
public interface RequestBook {

    Book load();

    void store(Book book);

    /**
     * Collections kept as files in the project's own folder ({@code .devdesk/requests/}), so teammates get them with
     * git. Empty when this book can't share.
     */
    default Set<String> sharedCollections() {
        return Set.of();
    }

    /** Moves a collection into the project's folder ({@code shared}), or back to this machine only. */
    default void setShared(String collection, boolean shared) {
        throw new UnsupportedOperationException("this request book can't share collections");
    }

    /** Requests kept in the history; older ones drop off. */
    int HISTORY_KEPT = 100;

    /**
     * A request the user saved, in a collection (a folder name; empty for none), with what to check in its answer
     * and which values of the answer to keep as variables.
     */
    record SavedRequest(
            String id,
            String name,
            String collection,
            HttpCall call,
            List<ResponseRules.Check> checks,
            List<ResponseRules.Capture> captures) {

        public SavedRequest {
            checks = List.copyOf(checks);
            captures = List.copyOf(captures);
        }

        public SavedRequest(String id, String name, String collection, HttpCall call) {
            this(id, name, collection, call, List.of(), List.of());
        }
    }

    /** A request that was sent, and how it went. {@code status} is empty when there was no answer. */
    record Sent(Instant at, HttpCall call, Optional<Integer> status, Duration took) {}

    /** Everything kept for one project. Changes return a new Book (records are immutable). */
    record Book(List<SavedRequest> saved, Map<String, String> variables, List<Sent> history) {

        public Book {
            saved = List.copyOf(saved);
            variables = Map.copyOf(variables);
            history = List.copyOf(history);
        }

        public static Book empty() {
            return new Book(List.of(), Map.of(), List.of());
        }

        /** Adds the request, or replaces the one with the same id, keeping its place in the list. */
        public Book withSaved(SavedRequest request) {
            List<SavedRequest> next = new ArrayList<>(saved);
            int at = indexOf(request.id());
            if (at >= 0) {
                next.set(at, request);
            } else {
                next.add(request);
            }
            return new Book(next, variables, history);
        }

        /** Adds many at once (an import), each new or replacing the one with its id. */
        public Book withAll(List<SavedRequest> requests) {
            Book b = this;
            for (SavedRequest r : requests) {
                b = b.withSaved(r);
            }
            return b;
        }

        public Book without(String id) {
            return new Book(saved.stream().filter(r -> !r.id().equals(id)).toList(), variables, history);
        }

        public Optional<SavedRequest> find(String id) {
            int at = indexOf(id);
            return at >= 0 ? Optional.of(saved.get(at)) : Optional.empty();
        }

        public Book withVariables(Map<String, String> next) {
            return new Book(saved, new LinkedHashMap<>(next), history);
        }

        /** Newest first, at most {@link #HISTORY_KEPT}. */
        public Book withSent(Sent sent) {
            List<Sent> next = new ArrayList<>();
            next.add(sent);
            next.addAll(history.subList(0, Math.min(history.size(), HISTORY_KEPT - 1)));
            return new Book(saved, variables, next);
        }

        private int indexOf(String id) {
            for (int i = 0; i < saved.size(); i++) {
                if (saved.get(i).id().equals(id)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
