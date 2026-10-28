package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.process.OutputLine;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds the line that says why in what a service printed: the error, not the end of its stack trace. Mastodon's
 * crash printed "Missing `secret_key_base`" and then twenty lines of "from /usr/local/bundle/...".
 */
final class ErrorLines {

    /** A stack frame or a trace's tail: Java/Kotlin "at", Ruby "from", Python "File", Go/Rust "#3", "... 12 more". */
    private static final Pattern FRAME =
            Pattern.compile("^\\s*(at |from |File \"|#\\d+ |\\.\\.\\. \\d+ more|\\^|\\||Traceback \\(most recent)");

    /** Words that name the cause: what's missing, wrong or refused. */
    private static final Pattern STRONG = Pattern.compile(
            "(?i)(missing|required|not set|must be|is not|invalid|undefined|does ?n[o']t exist|no such|not found"
                    + "|refused|denied|cannot|can't|unable to|is empty|unknown)");

    /** Words of a plain failure, when nothing says more. */
    private static final Pattern ERROR =
            Pattern.compile("(?i)(\\berror\\b|exception|fatal|panic|failed|\\bERR\\b|traceback)");

    private ErrorLines() {}

    /** The most telling error line: the last one naming a cause, else the last plain error. Never a stack frame. */
    static Optional<String> cause(List<OutputLine> lines) {
        String strong = null;
        String plain = null;
        for (OutputLine l : lines) {
            String t = l.text().strip();
            if (t.isEmpty() || FRAME.matcher(l.text()).find()) {
                continue;
            }
            if (STRONG.matcher(t).find() && ERROR.matcher(t).find()
                    || STRONG.matcher(t).find() && t.length() < 200) {
                strong = t;
            } else if (ERROR.matcher(t).find()) {
                plain = t;
            }
        }
        return Optional.ofNullable(strong != null ? strong : plain);
    }

    /** How many distinct error lines (not stack frames) these are. */
    static long distinctErrors(List<OutputLine> lines) {
        return lines.stream()
                .map(l -> l.text().strip())
                .filter(t -> !t.isEmpty() && ERROR.matcher(t).find())
                .filter(t -> !FRAME.matcher(t).find())
                .distinct()
                .count();
    }
}
