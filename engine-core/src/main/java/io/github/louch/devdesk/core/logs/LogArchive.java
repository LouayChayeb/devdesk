package io.github.louch.devdesk.core.logs;

import java.time.Instant;
import java.util.List;

/** Every line a project's services printed, kept for some days so it can be searched (the Linux adapter: files). */
public interface LogArchive {

    void append(LogLine line);

    /** Matching lines, newest first, at most {@code limit}. */
    List<LogLine> search(LogQuery.Matcher matcher, Instant since, int limit);

    /** The lines of {@code service} printed around {@code at}: {@code before} lines before it and {@code after} after. */
    List<LogLine> around(String service, Instant at, int before, int after);
}
