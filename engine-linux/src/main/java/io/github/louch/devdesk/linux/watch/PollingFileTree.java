package io.github.louch.devdesk.linux.watch;

import io.github.louch.devdesk.core.watch.FileTree;
import io.github.louch.devdesk.core.watch.WatchRules;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks at the files by walking the folders and reading each file's last-modified time. Polling, not inotify, on
 * purpose: on WSL, files under /mnt/c changed by Windows editors send no inotify events at all, and inotify's watch
 * limit (8192 by default) is easy to reach in a big project. A walk of a few thousand files takes milliseconds.
 */
public final class PollingFileTree implements FileTree {

    @Override
    public Map<Path, Long> scan(List<Path> folders, int limit) {
        Map<Path, Long> files = new HashMap<>();
        for (Path folder : folders) {
            if (Files.isRegularFile(folder)) {
                // watch: [api.py] watches one file: projects whose services are single scripts in one folder
                try {
                    files.put(folder, Files.getLastModifiedTime(folder).toMillis());
                } catch (IOException e) {
                    // deleted meanwhile: its absence is the change
                }
                continue;
            }
            if (!Files.isDirectory(folder)) {
                continue;
            }
            try {
                Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        boolean skipped = !dir.equals(folder)
                                && WatchRules.SKIPPED_FOLDERS.contains(
                                        dir.getFileName().toString());
                        return skipped ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isRegularFile() && !WatchRules.ignored(folder.relativize(file))) {
                            files.put(file, attrs.lastModifiedTime().toMillis());
                        }
                        return files.size() >= limit ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        return FileVisitResult.CONTINUE; // deleted meanwhile or unreadable: not a change we can act on
                    }
                });
            } catch (IOException e) {
                // the folder vanished during the walk: the next look sees what's left
            }
        }
        return files;
    }
}
