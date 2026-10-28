package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.state.ServiceState;
import java.util.List;
import java.util.Optional;

/**
 * MongoDB 8 refuses to start on Linux kernels 6.19 to 7.0.13: its memory allocator (TCMalloc) breaks with those
 * kernels' rseq changes, so it checks the kernel and exits (SERVER-121912). Its message is one JSON log line among
 * others, and nothing about the project is wrong: the fix is another MongoDB version or another kernel.
 */
public final class MongoKernelRule implements DiagnosisRule {

    @Override
    public String id() {
        return "mongo-kernel";
    }

    @Override
    public List<Finding> evaluate(DiagnosisContext ctx, String service) {
        ServiceState state = ctx.stateOf(service);
        boolean failed = state instanceof ServiceState.Crashed
                || state instanceof ServiceState.CrashLoop
                || state instanceof ServiceState.FailedToStart
                || state instanceof ServiceState.Unhealthy;
        if (!failed) {
            return List.of();
        }
        Optional<String> line = ctx.historyOf(service).recentOutput().stream()
                .map(OutputLine::text)
                .filter(t -> t.contains("SERVER-121912") || t.contains("MongoDB cannot start: Linux kernel versions"))
                .findFirst();
        if (line.isEmpty()) {
            return List.of();
        }
        return List.of(new Finding(
                id(),
                Finding.Severity.ERROR,
                service,
                service + ": MongoDB 8 can't run on this machine's Linux kernel",
                "MongoDB 8 stops at start on Linux kernels 6.19 to 7.0.13: its memory allocator is incompatible with"
                        + " them (MongoDB's issue SERVER-121912). Nothing in the project is wrong.",
                List.of("its log: " + line.get().strip()),
                List.of(
                        "use MongoDB 7 for now: image: mongo:7 in devdesk.yaml (check the project supports it)",
                        "or update the Linux kernel to 7.0.14 or later (uname -r shows yours)")));
    }
}
