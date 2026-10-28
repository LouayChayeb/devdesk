package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.Signal;
import io.github.louch.devdesk.core.process.StreamKind;
import io.github.louch.devdesk.core.state.ServiceState;
import java.util.ArrayList;
import java.util.List;

/**
 * The service crashed (or keeps crashing). The reason is almost always in its last output lines, so they become the
 * evidence. Skips the cases a more specific rule explains better (exit 127, outside SIGKILL).
 */
public final class CrashRule implements DiagnosisRule {

    private static final int LAST_LINES = 10;

    @Override
    public String id() {
        return "crash";
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        ServiceState state = ctx.stateOf(service);
        if (!(state instanceof ServiceState.Crashed) && !(state instanceof ServiceState.CrashLoop)) {
            return List.of();
        }
        List<ExitStatus> exits = ctx.historyOf(service).exits();
        ExitStatus last = exits.isEmpty() ? ExitStatus.of(1) : exits.getLast();
        if (last.code() == 127
                || last.code() == 126
                || last instanceof ExitStatus.KilledBySignal k && k.signal() == Signal.KILL) {
            return List.of();
        }
        boolean loop = state instanceof ServiceState.CrashLoop;
        List<String> evidence = new ArrayList<>();
        if (loop) {
            evidence.add("last exits: "
                    + exits.stream().map(e -> String.valueOf(e.code())).toList());
        }
        List<OutputLine> lines = ctx.historyOf(service).recentOutput();
        // Errors usually go to stderr; fall back to everything if it printed nothing there.
        List<OutputLine> errors =
                lines.stream().filter(l -> l.stream() == StreamKind.STDERR).toList();
        List<OutputLine> shown = errors.isEmpty() ? lines : errors;
        // A crash loop prints the same error each time: show each distinct line once.
        List<OutputLine> tail = shown.subList(Math.max(0, shown.size() - LAST_LINES), shown.size());
        // The line that says why, when the tail is only the end of a stack trace (Mastodon's "Missing
        // `secret_key_base`" came 20 lines of "from /usr/local/bundle/..." earlier).
        ErrorLines.cause(lines)
                .filter(c -> tail.stream().noneMatch(l -> l.text().strip().equals(c)))
                .ifPresent(c -> evidence.add("its error: " + c));
        tail.stream()
                .map(l -> (l.stream() == StreamKind.STDERR ? "stderr | " : "stdout | ") + l.text())
                .distinct()
                .forEach(evidence::add);
        if (lines.isEmpty()) {
            evidence.add("it printed nothing before exiting");
        }
        return List.of(new Finding(
                id(),
                Finding.Severity.ERROR,
                service,
                loop ? service + " keeps crashing" : service + " crashed",
                (loop
                                ? service + " crashed " + exits.size()
                                        + " times in a short time, so DevDesk stopped restarting it. "
                                : "")
                        + "It " + last.explanation() + ". Its last output usually says why.",
                evidence,
                List.of(
                        "read the last lines above for the error, fix it, then run devdesk up again",
                        "run the command by hand in its folder to see the full output")));
    }
}
