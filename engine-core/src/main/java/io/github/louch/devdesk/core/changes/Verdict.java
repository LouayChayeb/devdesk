package io.github.louch.devdesk.core.changes;

import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.ServiceSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns "what changed since it last worked" into a ranked verdict: each change is matched with what fails now, the
 * best match first. A match only makes a change LIKELY; PROVEN needs a replay ({@link ImageProof}), and only image
 * changes can be replayed.
 */
public final class Verdict {

    /** Below this, a change is listed but nothing really points at it. */
    static final int LIKELY_FROM = 50;

    private static final Pattern NUMBER = Pattern.compile("\\b\\d{2,5}\\b");

    private Verdict() {}

    /** Scores every change against what fails now and returns them best match first (equal scores keep their order). */
    public static List<Suspect> rank(ProjectSpec project, List<KnownGood.Change> changes, List<Symptom> symptoms) {
        List<Suspect> out = new ArrayList<>();
        for (KnownGood.Change c : changes) {
            out.add(judge(project, c, symptoms));
        }
        // Stable sort: equal scores keep KnownGood's order (images first, then tools...).
        out.sort(Comparator.comparingInt(Suspect::score).reversed());
        return out;
    }

    private static Suspect judge(ProjectSpec project, KnownGood.Change c, List<Symptom> symptoms) {
        List<String> reached = reached(project, c);
        List<Symptom> near = symptoms.stream()
                .filter(s -> reached.isEmpty() || reached.contains(s.service()))
                .toList();
        List<Symptom> failing = near.stream().filter(Symptom::startFailure).toList();
        List<String> reasons = new ArrayList<>();
        int score;
        switch (c.kind()) {
            case IMAGE -> {
                if (!failing.isEmpty()) {
                    score = 100;
                    failing.forEach(s -> reasons.add(s.service() + " runs this image and " + what(s)));
                } else if (!near.isEmpty()) {
                    score = 60;
                    near.forEach(
                            s -> reasons.add(s.service() + " runs this image and has a problem (" + s.rule() + ")"));
                } else {
                    score = 10;
                }
            }
            case ENV_REMOVED, ENV_FILE -> {
                List<Symptom> named = mentioning(symptoms, "missing-env", c.subject(), c.kind());
                if (!named.isEmpty()) {
                    score = 95;
                    named.forEach(
                            s -> reasons.add(s.service() + " expects a setting that is gone now (" + s.rule() + ")"));
                } else {
                    score = startScore(failing, 45, reasons, "it changed before");
                }
            }
            case ENV_ADDED -> score = startScore(failing, 25, reasons, "a new setting appeared before");
            case ENV_VALUE -> score = startScore(failing, 50, reasons, "this setting's value changed before");
            case LOCKFILE -> {
                boolean crash = failing.stream().anyMatch(s -> s.rule().equals("crash"));
                score = failing.isEmpty() ? 15 : crash ? 75 : 70;
                failing.forEach(s -> reasons.add(s.service() + " uses these dependencies and " + what(s)));
            }
            case TOOL -> {
                List<Symptom> named = symptoms.stream()
                        .filter(s ->
                                s.rule().equals("program-missing") && s.text().contains(c.subject()))
                        .toList();
                if (!named.isEmpty()) {
                    score = 90;
                    named.forEach(s -> reasons.add(s.service() + " can't find " + c.subject()));
                } else {
                    score = startScore(failing, 55, reasons, c.subject() + " changed version before");
                }
            }
            case CONFIG -> {
                List<Symptom> named = symptoms.stream()
                        .filter(s -> c.subject().contains(s.service()) || sharesPort(c.subject(), s.text()))
                        .toList();
                if (!named.isEmpty()) {
                    score = 60;
                    named.forEach(s -> reasons.add("this line is about " + s.service() + ", which " + what(s)));
                } else {
                    score = failing.isEmpty() ? 10 : 30;
                }
            }
            case CODE -> {
                score = failing.isEmpty() ? 15 : 65;
                failing.forEach(s -> reasons.add(s.service() + " runs this code and " + what(s)));
            }
            case BRANCH -> score = startScore(failing, 50, reasons, "the branch changed before");
            default -> score = 10;
        }
        Suspect.Confidence confidence =
                score >= LIKELY_FROM && !reasons.isEmpty() ? Suspect.Confidence.LIKELY : Suspect.Confidence.UNKNOWN;
        return new Suspect(c, confidence, score, reached, reasons, caveat(c, symptoms.isEmpty()));
    }

    /** A start failure somewhere this change reaches: a weaker match, timing only. */
    private static int startScore(List<Symptom> failing, int score, List<String> reasons, String because) {
        if (failing.isEmpty()) {
            return 10;
        }
        failing.forEach(s -> reasons.add(s.service() + " " + what(s) + ", and " + because));
        return score;
    }

    /** missing-env findings naming this variable (or, for a deleted env file, any missing-env finding). */
    private static List<Symptom> mentioning(List<Symptom> symptoms, String rule, String subject, KnownGood.Kind kind) {
        return symptoms.stream()
                .filter(s -> s.rule().equals(rule))
                .filter(s -> kind == KnownGood.Kind.ENV_FILE
                        || s.text().matches("(?s).*\\b" + Pattern.quote(subject) + "\\b.*"))
                .toList();
    }

    /** A devdesk.yaml line holding a port number the finding talks about. */
    private static boolean sharesPort(String line, String text) {
        Matcher m = NUMBER.matcher(line);
        while (m.find()) {
            if (text.matches("(?s).*\\b" + m.group() + "\\b.*")) {
                return true;
            }
        }
        return false;
    }

    private static String what(Symptom s) {
        return switch (s.rule()) {
            case "health" -> "fails its health check";
            case "crash" -> "crashes";
            case "program-missing" -> "can't find its program";
            case "killed-from-outside" -> "was killed";
            default -> "has a problem (" + s.rule() + ")";
        };
    }

    /** The services a change can affect; empty means all of them. */
    static List<String> reached(ProjectSpec project, KnownGood.Change c) {
        return switch (c.kind()) {
            case IMAGE ->
                project.services().values().stream()
                        .filter(s -> s.workload() instanceof Workload.Container k
                                && k.image().equals(c.subject()))
                        .map(ServiceSpec::name)
                        .toList();
            case LOCKFILE -> {
                Path dir = project.root().resolve(c.subject()).getParent();
                List<String> inDir = commands(project).stream()
                        .filter(s -> s.directory().normalize().equals(dir.normalize()))
                        .map(ServiceSpec::name)
                        .toList();
                // A lock file at the root of a monorepo belongs to every app under it.
                yield !inDir.isEmpty() || !dir.normalize().equals(project.root().normalize())
                        ? inDir
                        : commands(project).stream().map(ServiceSpec::name).toList();
            }
            case TOOL, CODE -> commands(project).stream().map(ServiceSpec::name).toList();
            default -> List.of();
        };
    }

    private static List<ServiceSpec> commands(ProjectSpec project) {
        return project.services().values().stream()
                .filter(s -> s.workload() instanceof Workload.Command)
                .toList();
    }

    private static String caveat(KnownGood.Change c, boolean nothingFails) {
        if (nothingFails) {
            return "Nothing fails right now, so nothing points at this change.";
        }
        return switch (c.kind()) {
            case IMAGE ->
                "Matched with what fails, not replayed yet: \"Prove it\" runs the previous image in a"
                        + " throwaway container.";
            case CODE, BRANCH -> "DevDesk doesn't replay old code: git bisect tests the commits with you.";
            case LOCKFILE -> "Matched with what fails only: DevDesk can't safely reinstall the old dependencies.";
            case ENV_ADDED, ENV_REMOVED, ENV_VALUE, ENV_FILE ->
                "Matched with what fails only: values are compared as fingerprints and never shown or replayed.";
            case TOOL, CONFIG -> "Matched with what fails only, not replayed.";
        };
    }
}
