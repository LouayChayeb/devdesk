package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.http.RequestBook;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved requests in two places: shared collections as one JSON file each in the project's folder
 * ({@code .devdesk/requests/orders.json}, to commit with the code), everything else (other collections, variables,
 * history) in the private state file. Variables never go in the project folder: they may hold tokens.
 */
public final class SharedRequestBook implements RequestBook {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RequestBook local;
    private final Path folder;

    public SharedRequestBook(RequestBook local, Path folder) {
        this.local = local;
        this.folder = folder;
    }

    public static SharedRequestBook forProject(Map<String, String> env, ProjectSpec project) {
        return new SharedRequestBook(
                JsonRequestBook.forProject(env, project),
                project.root().resolve(".devdesk").resolve("requests"));
    }

    @Override
    public synchronized Book load() {
        Book mine = local.load();
        List<SavedRequest> saved = new ArrayList<>(mine.saved());
        for (Map.Entry<String, List<SavedRequest>> file : files().entrySet()) {
            saved.removeIf(r -> r.collection().equals(file.getKey())); // the file wins over an old private copy
            saved.addAll(file.getValue());
        }
        return new Book(saved, mine.variables(), mine.history());
    }

    @Override
    public synchronized void store(Book book) {
        Set<String> shared = sharedCollections();
        Map<String, List<SavedRequest>> byFile = new LinkedHashMap<>();
        shared.forEach(c -> byFile.put(c, new ArrayList<>()));
        List<SavedRequest> mine = new ArrayList<>();
        for (SavedRequest r : book.saved()) {
            if (shared.contains(r.collection())) {
                byFile.get(r.collection()).add(r);
            } else {
                mine.add(r);
            }
        }
        byFile.forEach(this::write);
        local.store(new Book(mine, book.variables(), book.history()));
    }

    @Override
    public synchronized Set<String> sharedCollections() {
        return files().keySet();
    }

    @Override
    public synchronized void setShared(String collection, boolean shared) {
        Book all = load();
        List<SavedRequest> in = all.saved().stream()
                .filter(r -> r.collection().equals(collection))
                .toList();
        if (shared) {
            write(collection, in);
        } else {
            try {
                Files.deleteIfExists(fileOf(collection));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        store(all); // puts each request where it now belongs
    }

    /** "Shop API / orders" → "shop-api-orders.json": readable in a file list and safe on every file system. */
    static String fileName(String collection) {
        String slug = collection
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return (slug.isEmpty() ? "requests" : slug) + ".json";
    }

    private Path fileOf(String collection) {
        return folder.resolve(fileName(collection));
    }

    /** Collection name → its requests, for every file in the folder. A file that doesn't parse is skipped. */
    private Map<String, List<SavedRequest>> files() {
        Map<String, List<SavedRequest>> out = new LinkedHashMap<>();
        if (!Files.isDirectory(folder)) {
            return out;
        }
        try (DirectoryStream<Path> list = Files.newDirectoryStream(folder, "*.json")) {
            for (Path f : list) {
                try {
                    JsonNode root = JSON.readTree(Files.readString(f));
                    String collection = root.path("collection").asString("");
                    List<SavedRequest> requests = new ArrayList<>();
                    for (JsonNode n : root.path("requests")) {
                        SavedRequest r = JsonRequestBook.entry(n);
                        // The file names the collection once; each request belongs to it whatever it says.
                        requests.add(
                                new SavedRequest(r.id(), r.name(), collection, r.call(), r.checks(), r.captures()));
                    }
                    out.put(collection, requests);
                } catch (IOException | RuntimeException e) {
                    System.err.println("devdesk: skipping " + f + " (" + e.getMessage() + ")");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private void write(String collection, List<SavedRequest> requests) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("collection", collection);
        root.put(
                "requests",
                requests.stream()
                        .map(r -> {
                            Map<String, Object> m = new LinkedHashMap<>(JsonRequestBook.entry(r));
                            m.remove("collection"); // said once, above
                            return m;
                        })
                        .toList());
        try {
            Files.createDirectories(folder);
            Path file = fileOf(collection);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            // Pretty and with a final newline: the file is read in code review and diffs, not only by DevDesk.
            Files.writeString(tmp, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
