package io.github.louch.devdesk.data;

import io.github.louch.devdesk.core.data.DatabaseTarget;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Finds SQLite databases in a project folder: files named *.db, *.sqlite, *.sqlite3 or *.db3 that really start with
 * SQLite's 16-byte header ("SQLite format 3" and a zero byte), so a random "notes.db" isn't taken for one.
 */
public final class SqliteFiles {

    private static final byte[] HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> SKIPPED = Set.of(
            "node_modules",
            ".git",
            "target",
            "build",
            "dist",
            ".venv",
            "venv",
            "__pycache__",
            ".gradle",
            ".idea",
            ".angular",
            ".next",
            "vendor");
    /** Deep enough for "backend/prisma/dev.db"; shallow enough to stay fast in a big repository. */
    private static final int MAX_DEPTH = 4;
    /** Files looked at before giving up, so a huge folder never makes the Data page slow. */
    private static final int MAX_FILES = 20_000;

    private SqliteFiles() {}

    /**
     * Each file becomes a target named by its path in the project ("data/app.db" → "data:app.db": the name is used in
     * URLs, where "/" would split it).
     */
    public static List<DatabaseTarget> find(Path root) {
        List<DatabaseTarget> found = new ArrayList<>();
        int[] seen = {0};
        try {
            Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return !dir.equals(root)
                                    && SKIPPED.contains(dir.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++seen[0] > MAX_FILES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs.isRegularFile() && named(file) && isSqlite(file)) {
                        String relative = root.relativize(file).toString().replace('/', ':');
                        found.add(new DatabaseTarget(
                                relative,
                                DatabaseTarget.Kind.SQLITE,
                                "",
                                0,
                                "",
                                "",
                                file.toAbsolutePath().toString()));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE; // no permission on one folder: look at the rest
                }
            });
        } catch (IOException e) {
            // the project folder itself is unreadable: no files then
        }
        return found;
    }

    private static boolean named(Path file) {
        String n = file.getFileName().toString().toLowerCase();
        return n.endsWith(".db") || n.endsWith(".sqlite") || n.endsWith(".sqlite3") || n.endsWith(".db3");
    }

    static boolean isSqlite(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(HEADER.length), HEADER);
        } catch (IOException e) {
            return false;
        }
    }
}
