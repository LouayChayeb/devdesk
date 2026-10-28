package io.github.louch.devdesk.core.branch;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * "Switch branch and your database still has the other branch's tables": a service marked {@code per_branch: true}
 * gets its named volumes per git branch ("shop-db-data" on main, "shop-db-data--feature-login" on feature/login), so
 * each branch has its own data and switching back finds it again. The main branch keeps the plain names: data a
 * project already has stays the main branch's.
 */
public final class BranchStacks {

    /** Branches that keep the volumes' own names. */
    static final Set<String> MAIN = Set.of("main", "master", "trunk", "develop");

    private BranchStacks() {}

    /**
     * The branch git is on, from the text of {@code .git/HEAD}: "ref: refs/heads/feature/login" → "feature/login".
     * A detached HEAD (a commit checked out) has no branch: empty.
     */
    public static Optional<String> branch(String head) {
        String h = head.strip();
        return h.startsWith("ref: refs/heads/")
                ? Optional.of(h.substring("ref: refs/heads/".length()))
                : Optional.empty();
    }

    /**
     * Where a worktree's HEAD is: a worktree's {@code .git} is a file "gitdir: /repo/.git/worktrees/x" instead of a
     * folder. Returns that folder, or empty when {@code .git} is a normal folder.
     */
    public static Optional<String> gitDirOf(String dotGitFile) {
        String t = dotGitFile.strip();
        return t.startsWith("gitdir:")
                ? Optional.of(t.substring("gitdir:".length()).strip())
                : Optional.empty();
    }

    /** "feature/Login-2" → "feature-login-2": safe in a Docker volume name. Empty for a main branch. */
    public static String suffix(String branch) {
        if (MAIN.contains(branch)) {
            return "";
        }
        String s = branch.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.length() > 40 ? s.substring(0, 40).replaceAll("-$", "") : s;
    }

    /** The services whose data is kept per branch. */
    public static List<String> perBranch(ProjectSpec project) {
        return project.services().values().stream()
                .filter(ServiceSpec::perBranch)
                .map(ServiceSpec::name)
                .toList();
    }

    /**
     * The project as it runs on {@code branch}: per-branch services' named volumes renamed. Folders mounted from
     * disk ("./data:/data") are the files themselves and stay as they are. No branch, or a main one: unchanged.
     */
    public static ProjectSpec apply(ProjectSpec project, Optional<String> branch) {
        String suffix = branch.map(BranchStacks::suffix).orElse("");
        if (suffix.isEmpty() || perBranch(project).isEmpty()) {
            return project;
        }
        Map<String, ServiceSpec> services = new LinkedHashMap<>();
        project.services().forEach((name, s) -> {
            if (s.perBranch() && s.workload() instanceof Workload.Container c) {
                List<String> volumes =
                        c.volumes().stream().map(v -> renamed(v, suffix)).toList();
                services.put(
                        name,
                        new ServiceSpec(
                                s.name(),
                                new Workload.Container(
                                        c.image(), c.ports(), volumes, c.command(), c.build(), c.entrypoint()),
                                s.directory(),
                                s.environment(),
                                s.dependsOn(),
                                s.ports(),
                                s.health(),
                                s.restart(),
                                s.stopGracePeriod(),
                                s.watch(),
                                s.test(),
                                true,
                                s.sleepAfter()));
            } else {
                services.put(name, s);
            }
        });
        return new ProjectSpec(
                project.name(), project.root(), project.environment(), services, project.profiles(), project.tasks());
    }

    /** "pgdata:/var/lib/postgresql" → "pgdata--feature-login:/var/lib/postgresql"; "./data:/x" stays. */
    static String renamed(String volume, String suffix) {
        int colon = volume.indexOf(':');
        if (colon <= 0) {
            return volume; // an anonymous volume: new on every start anyway
        }
        String source = volume.substring(0, colon);
        boolean folder = source.startsWith(".") || source.startsWith("/") || source.startsWith("~");
        return folder ? volume : source + "--" + suffix + volume.substring(colon);
    }
}
