package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.changes.Bisect;
import io.github.louch.devdesk.core.changes.ChangeReport;
import io.github.louch.devdesk.core.changes.ImageProof;
import io.github.louch.devdesk.core.changes.Suspect;
import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/** Prints the "what changed since it last worked" verdict: ranked changes, why, the caveat, and what to do next. */
final class VerdictPrinter {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("MMM dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int COMMITS_SHOWN = 5;

    private VerdictPrinter() {}

    /**
     * @param proofs image → its replay, for the image changes that were proven (or tried) with --prove
     * @param proveHint true when --prove wasn't given: image changes then say how to prove them
     */
    static void print(
            PrintStream out,
            Instant since,
            ChangeReport.Report report,
            Map<String, ImageProof.Result> proofs,
            boolean proveHint) {
        out.println();
        if (report.items().isEmpty()) {
            out.println("✓ Nothing in the setup changed since it last worked (" + WHEN.format(since)
                    + "): look at the services' output.");
            return;
        }
        out.println("What changed since it last worked (" + WHEN.format(since) + "), most likely first:");
        int n = 1;
        for (ChangeReport.Item item : report.items()) {
            Optional<ImageProof.Result> proof = Optional.ofNullable(
                            proofs.get(item.suspect().change().subject()))
                    .filter(p -> item.suspect().provable());
            Suspect s = proof.map(p -> p.applyTo(item.suspect())).orElse(item.suspect());
            out.printf(
                    "  %d. %-8s %s %s: %s%n",
                    n++,
                    s.confidence().name(),
                    s.change().area(),
                    s.change().what(),
                    s.change().detail());
            s.reasons().forEach(r -> out.println("       why:  " + r));
            proof.ifPresent(p -> p.evidence().forEach(e -> out.println("       seen: " + e)));
            out.println("       note: " + s.caveat());
            if (proveHint && s.provable() && s.confidence() != Suspect.Confidence.UNKNOWN) {
                out.println("       next: devdesk why --prove   (replays the previous image in a throwaway"
                        + " container; your services, ports and volumes are not touched)");
            }
            item.bisect().ifPresent(b -> bisect(out, b));
        }
    }

    private static void bisect(PrintStream out, Bisect.Plan plan) {
        if (plan.commits().isEmpty()) {
            return;
        }
        out.println("       commits since then, those touching the failing services first:");
        plan.commits().stream()
                .limit(COMMITS_SHOWN)
                .forEach(r -> out.println("         "
                        + r.commit()
                                .id()
                                .substring(0, Math.min(10, r.commit().id().length()))
                        + "  " + r.commit().subject()
                        + (r.touching() > 0 ? "  (" + r.touching() + " file(s) in a failing service)" : "")));
        if (plan.commits().size() > COMMITS_SHOWN) {
            out.println("         ... " + (plan.commits().size() - COMMITS_SHOWN) + " more");
        }
        out.println("       find the bad commit with git bisect:");
        plan.commands().forEach(c -> out.println("         " + c));
    }
}
