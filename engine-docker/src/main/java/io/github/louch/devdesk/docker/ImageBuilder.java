package io.github.louch.devdesk.docker;

import io.github.louch.devdesk.core.process.LaunchException;
import io.github.louch.devdesk.core.process.OutputLine;
import io.github.louch.devdesk.core.process.StreamKind;
import io.github.louch.devdesk.core.project.Workload;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a project's image from its Dockerfile with the {@code docker build} command, not Docker's HTTP API: the
 * command uses BuildKit, so Dockerfiles with {@code # syntax=} lines or {@code RUN --mount=type=cache} build exactly as
 * they do for the developer by hand. The image gets DevDesk's labels, so "Remove" can find it and nothing else.
 */
final class ImageBuilder {

    /** "#9 [3/5] RUN npm ci": BuildKit's plain progress names the step it is on. */
    private static final Pattern STEP = Pattern.compile("^#\\d+ \\[(?:[\\w.-]+ )?(\\d+/\\d+)] (.+)$");

    private static final int LAST_LINES = 12;

    private final Map<String, String> environment;

    /** @param environment where to find {@code docker} (PATH) and, for tests, which builder (BUILDX_BUILDER) */
    ImageBuilder(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    /** The command line, without running it: also what a failed build tells the user to run by hand. */
    static List<String> command(
            String program, String image, Workload.Build build, Map<String, String> labels, boolean load) {
        List<String> cmd = new ArrayList<>(List.of(program, "build", "--progress=plain", "-t", image));
        cmd.addAll(List.of("-f", build.context().resolve(build.dockerfile()).toString()));
        labels.forEach((k, v) -> cmd.addAll(List.of("--label", k + "=" + v)));
        if (load) {
            cmd.add("--load"); // a builder of its own keeps the result in its cache unless told to load it
        }
        cmd.add(build.context().toString());
        return cmd;
    }

    void build(String image, Workload.Build build, Map<String, String> labels, Consumer<OutputLine> output)
            throws LaunchException {
        if (!Files.isRegularFile(build.context().resolve(build.dockerfile()))) {
            throw new LaunchException(
                    "can't build " + image + ": there is no " + build.dockerfile() + " in " + build.context()
                            + ". Check build: in devdesk.yaml",
                    null);
        }
        String program = program()
                .orElseThrow(() -> new LaunchException(
                        "can't build " + image
                                + ": DevDesk builds images with the docker command (as \"docker build\" does),"
                                + " and it isn't installed. Install Docker's command-line tool, then start again",
                        null));
        boolean load = environment.containsKey("BUILDX_BUILDER") && program.endsWith("/docker");
        List<String> cmd = command(program, image, build, labels, load);
        output.accept(new OutputLine(
                StreamKind.STDERR,
                "[devdesk] building image " + image + " from " + build.context().resolve(build.dockerfile()) + " ..."));
        Attempt first = run(cmd, image, output);
        if (first.code() == 0) {
            output.accept(new OutputLine(StreamKind.STDERR, "[devdesk] built " + image));
            return;
        }
        if (!offline(first.last())) {
            throw new LaunchException(explain(image, first.step(), first.last()), null);
        }
        // On WSL (and some VPNs) the DNS server the machine uses can't be reached from Docker's build network.
        // The host's own network can: the same thing people do by hand with docker build --network host.
        output.accept(new OutputLine(
                StreamKind.STDERR,
                "[devdesk] the build couldn't reach the internet through Docker's network; trying again with this"
                        + " machine's network (--network host)"));
        List<String> host = new ArrayList<>(cmd);
        host.addAll(host.size() - 1, List.of("--network", "host"));
        Attempt second = run(host, image, output);
        if (second.code() != 0) {
            throw new LaunchException(explain(image, second.step(), second.last()), null);
        }
        output.accept(new OutputLine(StreamKind.STDERR, "[devdesk] built " + image));
    }

    /** One run of the build: its exit code, the step it was on, and its last lines. */
    private record Attempt(int code, String step, List<String> last) {}

    private Attempt run(List<String> cmd, String image, Consumer<OutputLine> output) throws LaunchException {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(environment);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new LaunchException("can't build " + image + ": " + e.getMessage(), e);
        }
        Deque<String> last = new ArrayDeque<>();
        String step = "";
        try (BufferedReader in =
                new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                output.accept(new OutputLine(StreamKind.STDERR, "[build] " + line));
                Matcher m = STEP.matcher(line);
                if (m.matches() && !m.group(2).equals("DONE")) {
                    step = m.group(1) + " (" + m.group(2) + ")";
                }
                last.addLast(line);
                if (last.size() > LAST_LINES) {
                    last.removeFirst();
                }
            }
            return new Attempt(p.waitFor(), step, List.copyOf(last));
        } catch (IOException e) {
            throw new LaunchException("can't build " + image + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            // A stop arrived while building: end the build, so nothing keeps going in the background.
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            Thread.currentThread().interrupt();
            throw new LaunchException("stopped while building " + image, e);
        }
    }

    private static boolean offline(List<String> last) {
        String text = String.join("\n", last).toLowerCase();
        return text.contains("temporary failure in name resolution")
                || text.contains("i/o timeout")
                || text.contains("could not resolve")
                || text.contains("tls handshake timeout");
    }

    /** Which step failed, the lines that say why, and the usual cause behind them. */
    static String explain(String image, String step, List<String> last) {
        String text = String.join("\n", last).toLowerCase();
        String cause;
        if (text.contains("no space left")) {
            cause = "The disk is full. Free some space (docker system df shows what Docker uses), then start again.";
        } else if (offline(last)) {
            cause = "The build couldn't reach the internet (it downloads a base image or packages). Check you're"
                    + " online, then start again.";
        } else if (text.contains("not found") && (text.contains("copy") || text.contains("checksum"))) {
            cause = "The Dockerfile copies a file that isn't in the folder it builds from: maybe the project expects"
                    + " a step first (a build, a generated file), or .dockerignore leaves it out.";
        } else if (text.contains("did not complete successfully")) {
            cause = "A command in the Dockerfile failed; the lines above say why.";
        } else {
            cause = "The lines above say why.";
        }
        String where = step.isEmpty() ? "" : " at step " + step;
        return "building image " + image + " failed" + where + ". " + cause + "\nLast lines:\n"
                + String.join("\n", last.subList(Math.max(0, last.size() - 6), last.size()));
    }

    /** {@code docker}, or {@code podman} (which builds the same way) when only Podman is installed. */
    private Optional<String> program() {
        for (String dir : environment.getOrDefault("PATH", "").split(":")) {
            for (String candidate : List.of("docker", "podman")) {
                if (!dir.isEmpty() && Files.isExecutable(Path.of(dir, candidate))) {
                    return Optional.of(Path.of(dir, candidate).toString());
                }
            }
        }
        return Optional.empty();
    }
}
