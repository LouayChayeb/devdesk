package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.process.ExitStatus;
import io.github.louch.devdesk.core.process.Signal;
import io.github.louch.devdesk.core.project.Workload;
import java.util.List;

/**
 * The service died from SIGKILL (exit 137) that DevDesk didn't send. Usually the kernel's out-of-memory killer, which
 * picks a big process and kills it without warning when the machine (or a container's limit) runs out of RAM.
 */
public final class KilledFromOutsideRule implements DiagnosisRule {

    @Override
    public String id() {
        return "killed-from-outside";
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        ServiceHistory h = ctx.historyOf(service);
        if (h.exits().isEmpty() || h.stoppedByDevDesk()) {
            return List.of();
        }
        if (!(h.exits().getLast() instanceof ExitStatus.KilledBySignal k) || k.signal() != Signal.KILL) {
            return List.of();
        }
        boolean container = ctx.project().services().get(service).workload() instanceof Workload.Container;
        String check = container
                ? "docker inspect devdesk-" + ctx.project().name() + "-" + service + " --format '{{.State.OOMKilled}}'"
                        + "   (true = it hit its memory limit)"
                : "journalctl -k --since '15 min ago' | grep -i -E 'out of memory|oom'";
        return List.of(new Finding(
                id(),
                Finding.Severity.ERROR,
                service,
                service + " was killed from outside DevDesk",
                service + " ended with SIGKILL (code 137), and DevDesk didn't send it. The most common cause is the "
                        + "kernel's out-of-memory killer: when memory runs out, it kills a large process without warning. "
                        + "Something else (a script, you, a monitor) may also have run kill -9.",
                List.of("exit status: " + k.explanation(), "DevDesk did not stop " + service + " itself"),
                List.of(
                        "check for an out-of-memory kill: " + check,
                        "if it was memory: give the machine/container more RAM, or make " + service + " use less")));
    }
}
