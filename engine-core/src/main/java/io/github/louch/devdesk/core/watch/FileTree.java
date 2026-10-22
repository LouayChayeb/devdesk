package io.github.louch.devdesk.core.watch;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** The port the watcher uses to look at files; the Linux adapter walks the folders. */
public interface FileTree {

    /**
     * Every file under {@code folders} that {@link WatchRules} doesn't ignore, with its last-modified time (millis).
     * Stops after {@code limit} files: a watched folder that big is a mistake (a dependency folder not skipped).
     */
    Map<Path, Long> scan(List<Path> folders, int limit);
}
