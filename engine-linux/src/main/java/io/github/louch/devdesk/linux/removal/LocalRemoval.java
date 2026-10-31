package io.github.louch.devdesk.linux.removal;

import io.github.louch.devdesk.core.leftovers.Leftover;
import io.github.louch.devdesk.core.leftovers.LeftoverFinder;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.removal.Removable;
import io.github.louch.devdesk.core.removal.RemovalPort;
import io.github.louch.devdesk.linux.get.GitClone;
import io.github.louch.devdesk.linux.trust.TrustStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What DevDesk made on this machine for one project: processes it started that still run (found by its scope or
 * group records), what it saved about the project (logs, history, known-good…, all named by the project's key), its
 * trust in the project's devdesk.yaml, and the project's folder only when DevDesk cloned it.
 */
public final class LocalRemoval implements RemovalPort {

    private final LeftoverFinder processes;
    private final Path state;
    private final TrustStore trust;
    private final GitClone clones;
    /** The processes the last find saw, by id: deleting one needs what the finder returned. */
    private final java.util.Map<String, Leftover> seen = new java.util.concurrent.ConcurrentHashMap<>();

    /** Deletes files another user owns inside a folder; empty when it did, otherwise why not. */
    public interface RootFiles {
        /** Deletes the other user's files inside {@code folder}; empty when it worked, otherwise why not. */
        Optional<String> deleteInside(Path folder);
    }

    private final Optional<RootFiles> rootFiles;

    /** @param state DevDesk's state folder ({@code ~/.local/state/devdesk}) */
    public LocalRemoval(LeftoverFinder processes, Path state, TrustStore trust, GitClone clones) {
        this(processes, state, trust, clones, Optional.empty());
    }

    /** @param rootFiles deletes container-written files in a clone (Docker can, as their owner); empty: none */
    public LocalRemoval(
            LeftoverFinder processes, Path state, TrustStore trust, GitClone clones, Optional<RootFiles> rootFiles) {
        this.processes = processes;
        this.state = state;
        this.trust = trust;
        this.clones = clones;
        this.rootFiles = rootFiles;
    }

    @Override
    public List<Removable> find(ProjectSpec project, boolean cloned) {
        List<Removable> found = new ArrayList<>();
        processes.find(project).stream()
                .filter(l -> l.kind() == Leftover.Kind.PROCESS)
                .peek(l -> seen.put(l.id(), l))
                .forEach(l -> found.add(new Removable(
                        Removable.Kind.PROCESS,
                        l.id(),
                        "still running: " + l.service() + " (" + l.description() + ")",
                        false)));
        String key = project.key();
        // state/<key>.lock, and state/<kind>/<key> or state/<kind>/<key>.json: the key holds a hash of the folder,
        // so no other project's files match.
        try (Stream<Path> top = list(state)) {
            top.forEach(p -> {
                if (named(p, key)) {
                    found.add(stateItem(p));
                } else if (Files.isDirectory(p)) {
                    try (Stream<Path> inner = list(p)) {
                        inner.filter(q -> named(q, key)).forEach(q -> found.add(stateItem(q)));
                    }
                }
            });
        }
        Path file = project.root().resolve("devdesk.yaml");
        if (trust.knows(file)) {
            found.add(new Removable(
                    Removable.Kind.STATE,
                    "trust:" + file,
                    "your trust in " + file + " (DevDesk will ask again)",
                    false));
        }
        Optional<Path> clone = cloned ? clones.cloneOf(project.root()) : Optional.empty();
        clone.filter(folder -> othersInside(folder, project).isEmpty())
                .ifPresent(folder -> found.add(new Removable(
                        Removable.Kind.FOLDER,
                        folder.toString(),
                        "the folder " + folder + " (DevDesk cloned it; changes you made in it go too)",
                        true)));
        return found;
    }

    @Override
    public List<String> keeps(ProjectSpec project, boolean cloned) {
        Optional<Path> clone = cloned ? clones.cloneOf(project.root()) : Optional.empty();
        if (clone.isEmpty()) {
            return List.of();
        }
        List<Path> others = othersInside(clone.get(), project);
        return others.isEmpty()
                ? List.of()
                : List.of("the folder " + clone.get() + ": DevDesk cloned it, but it also holds "
                        + (others.size() == 1 ? "another project" : others.size() + " other projects") + " ("
                        + String.join(
                                ", ",
                                others.stream()
                                        .map(f -> f.getParent().toString())
                                        .toList())
                        + "), so it stays. Remove those first, or delete the folder yourself");
    }

    /** Other projects DevDesk knows (it was allowed to run them) whose devdesk.yaml is inside this clone. */
    private List<Path> othersInside(Path clone, ProjectSpec project) {
        Path own = project.root().resolve("devdesk.yaml").toAbsolutePath().normalize();
        return trust.knownFiles().stream()
                .map(f -> f.toAbsolutePath().normalize())
                .filter(f -> f.startsWith(clone) && !f.equals(own) && Files.exists(f))
                .toList();
    }

    @Override
    public Optional<String> delete(Removable r) {
        try {
            switch (r.kind()) {
                case PROCESS -> {
                    Leftover l = seen.get(r.id());
                    if (l == null) {
                        return Optional.empty(); // not running any more
                    }
                    String result = processes.remove(l);
                    return result.equals("stopped") || result.startsWith("force-killed")
                            ? Optional.empty()
                            : Optional.of(result);
                }
                case STATE -> {
                    if (r.id().startsWith("trust:")) {
                        trust.forget(Path.of(r.id().substring("trust:".length())));
                    } else {
                        deleteTree(Path.of(r.id()));
                    }
                }
                case FOLDER -> {
                    Path folder = Path.of(r.id());
                    if (!clones.cloned(folder)) {
                        return Optional.of("DevDesk didn't clone it, so it stays"); // checked again, at the last moment
                    }
                    Optional<String> left = deleteClone(folder);
                    if (left.isPresent()) {
                        return left;
                    }
                    clones.forget(folder);
                }
                default -> {
                    return Optional.of("not something of this machine's");
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            return Optional.of(e.getMessage());
        }
    }

    /**
     * Deletes a clone. Containers often write data into it as another user (a database's folder belongs to the
     * postgres user of its image, uid 70): this user can't delete those, so the owner of root's files is asked to
     * delete them, inside the clone and nowhere else. Empty when it's gone; otherwise what's left and how to remove it.
     */
    private Optional<String> deleteClone(Path folder) throws IOException {
        try {
            deleteTree(folder);
            return Optional.empty();
        } catch (java.nio.file.AccessDeniedException | java.io.UncheckedIOException e) {
            // Files another user owns (written by a container): let the root-files helper take those, then retry.
        }
        Optional<String> helped = rootFiles.flatMap(h -> h.deleteInside(folder));
        try {
            deleteTree(folder);
            return Optional.empty();
        } catch (IOException | java.io.UncheckedIOException e) {
            return Optional.of(helped.map(why -> why + ". ").orElse("")
                    + "some files in it belong to another user (a container wrote them), so DevDesk couldn't delete"
                    + " it. Delete it yourself: sudo rm -rf " + folder);
        }
    }

    private static boolean named(Path p, String key) {
        String n = p.getFileName().toString();
        return n.equals(key) || n.startsWith(key + ".");
    }

    private Removable stateItem(Path p) {
        String kind = p.getParent().equals(state)
                ? "lock"
                : p.getParent().getFileName().toString();
        return new Removable(Removable.Kind.STATE, p.toString(), "DevDesk's saved " + kind + " for it", false);
    }

    private static Stream<Path> list(Path dir) {
        try {
            return Files.isDirectory(dir) ? Files.list(dir) : Stream.empty();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    /** Deletes a file or a folder with everything in it; symbolic links are removed, never followed. */
    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path q : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(q);
            }
        }
    }
}
