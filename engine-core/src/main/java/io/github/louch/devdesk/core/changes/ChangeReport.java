package io.github.louch.devdesk.core.changes;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The whole "what changed since it last worked?" answer, shared by {@code devdesk why} and the app: compares the last
 * clean start with now, ranks the changes against what fails ({@link Verdict}), and adds a git bisect plan to a code
 * change.
 */
public final class ChangeReport {

    /** One ranked change, with its bisect plan when it's a code change and git could list the commits. */
    public record Item(Suspect suspect, Optional<Bisect.Plan> bisect) {}

    /**
     * @param since when the project last started cleanly
     * @param items most likely first
     */
    public record Report(Instant since, List<Item> items) {
        public Report {
            items = List.copyOf(items);
        }
    }

    private final Function<ProjectSpec, Optional<KnownGood>> lastGood;
    private final Function<ProjectSpec, KnownGood> now;
    private final BiFunction<Path, String, Optional<List<Bisect.Commit>>> commitsSince;

    /**
     * @param lastGood the saved picture of the last clean start
     * @param now the picture right now
     * @param commitsSince (project root, good commit) → commits since, paths relative to the root
     */
    public ChangeReport(
            Function<ProjectSpec, Optional<KnownGood>> lastGood,
            Function<ProjectSpec, KnownGood> now,
            BiFunction<Path, String, Optional<List<Bisect.Commit>>> commitsSince) {
        this.lastGood = lastGood;
        this.now = now;
        this.commitsSince = commitsSince;
    }

    /** Empty when DevDesk never saw this project start cleanly: there's nothing to compare with. */
    public Optional<Report> build(ProjectSpec project, List<Symptom> symptoms) {
        Optional<KnownGood> then = lastGood.apply(project);
        if (then.isEmpty()) {
            return Optional.empty();
        }
        List<KnownGood.Change> changes = KnownGood.compare(then.get(), now.apply(project));
        List<Item> items = new ArrayList<>();
        for (Suspect s : Verdict.rank(project, changes, symptoms)) {
            Optional<Bisect.Plan> plan = Optional.empty();
            if (s.change().kind() == KnownGood.Kind.CODE) {
                String good = s.change().subject();
                plan = commitsSince
                        .apply(project.root(), good)
                        .map(commits -> Bisect.plan(commits, folders(project, s, symptoms), good));
            }
            items.add(new Item(s, plan));
        }
        return Optional.of(new Report(then.get().at(), items));
    }

    /** The image id {@code image} ("postgres:latest") pointed to at the last clean start, for "Prove it". */
    public Optional<String> previousImage(ProjectSpec project, String image) {
        return lastGood.apply(project)
                .flatMap(k -> Optional.ofNullable(k.images().get(image)));
    }

    /** The failing services' folders, relative to the root: commits touching them are the first suspects. */
    private static List<String> folders(ProjectSpec project, Suspect s, List<Symptom> symptoms) {
        Set<String> failing = new LinkedHashSet<>();
        symptoms.stream()
                .filter(Symptom::startFailure)
                .map(Symptom::service)
                .filter(name -> s.services().isEmpty() || s.services().contains(name))
                .forEach(failing::add);
        if (failing.isEmpty()) {
            failing.addAll(s.services());
        }
        List<String> out = new ArrayList<>();
        for (String name : failing) {
            ServiceSpec spec = project.services().get(name);
            if (spec != null) {
                out.add(project.root().relativize(spec.directory()).toString());
            }
        }
        return out;
    }
}
