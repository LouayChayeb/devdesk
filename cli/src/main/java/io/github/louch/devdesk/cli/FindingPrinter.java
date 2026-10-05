package io.github.louch.devdesk.cli;

import io.github.louch.devdesk.core.diagnosis.Finding;
import java.io.PrintStream;

/** Prints a finding as a short block: what, why, the evidence, and the fix to copy. */
final class FindingPrinter {

    private FindingPrinter() {}

    static void print(PrintStream out, Finding f) {
        out.println("  " + (f.severity() == Finding.Severity.ERROR ? "✗" : "!") + " " + f.title());
        out.println("      why:  " + f.explanation());
        f.evidence().forEach(e -> out.println("      seen: " + e));
        f.actions().forEach(a -> out.println("      fix:  " + a));
    }
}
