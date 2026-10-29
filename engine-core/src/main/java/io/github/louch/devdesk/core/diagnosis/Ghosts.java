package io.github.louch.devdesk.core.diagnosis;

import io.github.louch.devdesk.core.network.Listener;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * "Ghosts": your own processes running in a project's folder that DevDesk didn't start, like an `npm run dev` left
 * running in another terminal. They are only reported, never stopped: DevDesk never stops what it didn't start.
 */
public final class Ghosts {

    /**
     * One ghost: the top process of a family (e.g. `npm run dev`), with every port it or its children listen on.
     *
     * @param children how many more processes belong to it (sh, node, workers...)
     */
    public record Ghost(ProcessInfo process, Set<Integer> ports, int children) {

        /** Services that need a port this ghost holds, with that port: they can't start while it runs. */
        public Map<String, Integer> blocks(ProjectSpec project) {
            Map<String, Integer> blocked = new LinkedHashMap<>();
            project.services()
                    .values()
                    .forEach(s -> s.ports().stream()
                            .filter(ports::contains)
                            .findFirst()
                            .ifPresent(port -> blocked.put(s.name(), port)));
            return blocked;
        }
    }

    /** Programs that are typically long-running dev servers or tools. Anything listening on a port counts too. */
    private static final Set<String> DEV_PROGRAMS = Set.of(
            "node",
            "npm",
            "npx",
            "yarn",
            "pnpm",
            "bun",
            "deno",
            "python",
            "python3",
            "uvicorn",
            "gunicorn",
            "flask",
            "java",
            "mvn",
            "gradle",
            "ruby",
            "rails",
            "php",
            "go",
            "dotnet",
            "cargo",
            "vite",
            "next",
            "ng",
            "nest",
            "air",
            "nodemon",
            "tsx",
            "ts-node");

    private Ghosts() {}

    /**
     * @param managed PIDs that a DevDesk owns right now (they are services, not ghosts)
     * @param devdesks PIDs of DevDesks: the one asking, and the one running the project. They and their ancestors
     *     (the shell, the terminal) are skipped
     */
    public static List<Ghost> find(ProjectSpec project, Introspection system, Set<Long> managed, Set<Long> devdesks) {
        Set<Long> skip = new HashSet<>(managed);
        for (long devdesk : devdesks) {
            for (long pid = devdesk; pid > 1 && skip.add(pid); ) {
                pid = system.process(pid).map(ProcessInfo::parentPid).orElse(0L);
            }
        }
        Set<String> programs = new HashSet<>(DEV_PROGRAMS);
        project.services().values().forEach(s -> {
            if (s.workload() instanceof Workload.Command c) {
                ProgramMissingRule.firstProgram(c.commandLine())
                        .ifPresent(p -> programs.add(p.substring(p.lastIndexOf('/') + 1)));
            }
        });

        Map<Long, ProcessInfo> inFolder = new LinkedHashMap<>();
        system.processesIn(project.root()).stream()
                .filter(p -> !skip.contains(p.pid()))
                .filter(p -> !interactiveShell(p.command())) // your terminal is where you typed it, not the ghost
                .forEach(p -> inFolder.put(p.pid(), p));
        // Group each process under its highest ancestor that is also in the folder: `npm` → `sh` → `node` is one ghost.
        Map<Long, List<ProcessInfo>> families = new LinkedHashMap<>();
        for (ProcessInfo p : inFolder.values()) {
            long top = p.pid();
            while (inFolder.containsKey(inFolder.get(top).parentPid())) {
                top = inFolder.get(top).parentPid();
            }
            families.computeIfAbsent(top, k -> new ArrayList<>()).add(p);
        }
        List<Ghost> ghosts = new ArrayList<>();
        families.forEach((top, members) -> {
            Set<Long> pids = new HashSet<>();
            members.forEach(m -> pids.add(m.pid()));
            Set<Integer> ports = new TreeSet<>();
            system.listenersOf(pids).stream().map(Listener::port).forEach(ports::add);
            boolean devProgram = members.stream().anyMatch(m -> programs.contains(program(m.command())));
            if (!ports.isEmpty() || devProgram) {
                ghosts.add(new Ghost(inFolder.get(top), ports, members.size() - 1));
            }
        });
        return ghosts;
    }

    private static final Set<String> SHELLS = Set.of("bash", "zsh", "fish", "sh", "dash", "-bash", "-zsh");

    /** A shell you type into ("bash"), as opposed to one running a command ("sh -c npm start"). */
    static boolean interactiveShell(String command) {
        return SHELLS.contains(program(command)) && !command.contains(" -c ");
    }

    /** "node" for "/usr/bin/node server.js". */
    static String program(String command) {
        String first = command.strip().split("\\s+")[0];
        return first.substring(first.lastIndexOf('/') + 1);
    }

    /** For callers that only have one PID to check. */
    static Optional<Ghost> containing(List<Ghost> ghosts, long pid) {
        return ghosts.stream().filter(g -> g.process().pid() == pid).findFirst();
    }
}
