package io.github.louch.devdesk.core.logs;

import java.time.Instant;

/** One line a service printed, as kept for searching later. {@code stream} is "stdout" or "stderr". */
public record LogLine(Instant at, String service, String stream, String text) {}
