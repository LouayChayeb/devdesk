package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.setup.EnvReport;
import io.github.louch.devdesk.core.setup.FolderScan;
import io.github.louch.devdesk.core.setup.FreshSetup;
import io.github.louch.devdesk.core.setup.SetupDraft;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The setup wizard's draft as JSON, both ways. Input is checked field by field: it comes from outside the engine. */
final class SetupJson {

    private SetupJson() {}

    static Map<String, Object> draft(SetupDraft d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.name());
        m.put("envFiles", d.envFiles());
        m.put("services", d.services().stream().map(SetupJson::service).toList());
        m.put("notes", d.notes());
        m.put("kept", d.kept());
        return m;
    }

    static Map<String, Object> service(SetupDraft.Service s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.name());
        m.put("kind", s.kind());
        m.put("command", s.command());
        m.put("directory", s.directory());
        m.put("image", s.image());
        m.put("ports", s.ports());
        m.put("volumes", s.volumes());
        m.put("environment", s.environment());
        m.put("dependsOn", s.dependsOn());
        m.put(
                "health",
                Map.of(
                        "type",
                        s.health().type(),
                        "target",
                        s.health().target(),
                        "startPeriod",
                        s.health().startPeriod()));
        m.put("restart", s.restart());
        m.put("origin", s.origin());
        m.put("notes", s.notes());
        m.put("extras", s.extras());
        // Shown as a database box in the wizard; read-only, never sent back.
        m.put("dataStore", s.isContainer() && FolderScan.isDataStore(s.image()));
        return m;
    }

    /** A fresh-setup step for the app: what it does and the exact command, shown before it runs. */
    static Map<String, Object> step(FreshSetup.Step step) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", step.id());
        m.put("title", step.title());
        switch (step) {
            case FreshSetup.Step.Run r -> {
                m.put("kind", "run");
                m.put("directory", r.directory());
                m.put("command", r.command());
            }
            case FreshSetup.Step.CreateEnv e -> {
                m.put("kind", "env");
                m.put("file", e.file());
                m.put(
                        "questions",
                        e.questions().stream()
                                .map(q -> Map.of("key", q.key(), "example", q.example(), "secret", q.secret()))
                                .toList());
            }
            case FreshSetup.Step.FillEnv f -> {
                m.put("kind", "env");
                m.put("file", f.file());
                m.put(
                        "questions",
                        f.questions().stream()
                                .map(q -> Map.of("key", q.key(), "example", q.example(), "secret", q.secret()))
                                .toList());
            }
            case FreshSetup.Step.Job j -> {
                m.put("kind", "job");
                m.put("service", j.service());
            }
        }
        return m;
    }

    static Map<String, Object> envReport(EnvReport.Result r) {
        return Map.of(
                "variables",
                r.variables().stream()
                        .map(v -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("key", v.key());
                            m.put("file", v.file());
                            m.put("value", v.value().orElse(null)); // null for secrets: the app shows ●●●●
                            m.put("secret", v.secret());
                            m.put("usedBy", v.usedBy());
                            return m;
                        })
                        .toList(),
                "missing",
                r.missing().stream()
                        .map(x -> Map.of("key", x.key(), "reason", x.reason()))
                        .toList(),
                "warnings",
                r.warnings());
    }

    /** The draft the app sent, or empty when a field has the wrong type (the answer then says what's expected). */
    static Optional<SetupDraft> readDraft(Object o) {
        if (!(o instanceof Map<?, ?> m) || !(m.get("services") instanceof List<?> list)) {
            return Optional.empty();
        }
        List<SetupDraft.Service> services = new ArrayList<>();
        for (Object s : list) {
            Optional<SetupDraft.Service> service = readService(s);
            if (service.isEmpty()) {
                return Optional.empty();
            }
            services.add(service.get());
        }
        return Optional.of(new SetupDraft(
                string(m.get("name")).orElse("project"),
                strings(m.get("envFiles")),
                services,
                strings(m.get("notes")),
                // Sections of the file the wizard doesn't edit (profiles:): sent back unchanged, but not via strip()
                m.get("kept") instanceof String kept ? kept : ""));
    }

    private static Optional<SetupDraft.Service> readService(Object o) {
        if (!(o instanceof Map<?, ?> m) || string(m.get("name")).isEmpty()) {
            return Optional.empty();
        }
        Map<?, ?> h = m.get("health") instanceof Map<?, ?> hm ? hm : Map.of();
        String kind = "container".equals(m.get("kind")) ? "container" : "command";
        return Optional.of(new SetupDraft.Service(
                string(m.get("name")).orElseThrow().strip(),
                kind,
                string(m.get("command")).orElse("").strip(),
                string(m.get("directory")).orElse("").strip(),
                string(m.get("image")).orElse("").strip(),
                strings(m.get("ports")),
                strings(m.get("volumes")),
                map(m.get("environment")),
                map(m.get("dependsOn")),
                new SetupDraft.Health(
                        string(h.get("type")).orElse("process"),
                        string(h.get("target")).orElse("").strip(),
                        string(h.get("startPeriod")).orElse("").strip()),
                string(m.get("restart")).orElse("no"),
                string(m.get("origin")).orElse(""),
                strings(m.get("notes")),
                map(m.get("extras"))));
    }

    private static Optional<String> string(Object o) {
        // Numbers are fine too: a port typed in a number field arrives as 3000, not "3000".
        return o instanceof String s
                ? Optional.of(s)
                : o instanceof Number n ? Optional.of(n.toString()) : Optional.empty();
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .flatMap(i -> string(i).stream())
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static Map<String, String> map(Object o) {
        Map<String, String> out = new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) {
            m.forEach((k, v) -> {
                if (k instanceof String key && !key.isBlank()) {
                    out.put(key.strip(), string(v).orElse(""));
                }
            });
        }
        return out;
    }
}
