package io.github.louch.devdesk.daemon;

import io.github.louch.devdesk.core.bundle.Redact;
import io.github.louch.devdesk.core.supervisor.ServiceEnvironment;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * "Pack it up for whoever helps": one zip with the services' states, findings, logs, the last day's timeline, the
 * settings (keys and where they come from) and devdesk.yaml. Every secret value is taken out first, wherever it
 * appears, so the file can be attached to an issue or a chat.
 */
final class CrashBundle {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final EngineSession session;
    private final ChangeTracker changes;
    private final Path defaultFolder;
    private final Clock clock;

    CrashBundle(EngineSession session, ChangeTracker changes, Path defaultFolder, Clock clock) {
        this.session = session;
        this.changes = changes;
        this.defaultFolder = defaultFolder;
        this.clock = clock;
    }

    /** {path?}: writes the bundle there, or into DevDesk's own folder when no path is given. */
    EngineSession.Answer write(Map<?, ?> body) {
        Optional<EngineSession.BundleParts> parts = session.bundleParts();
        if (parts.isEmpty()) {
            return new EngineSession.Answer(404, ApiJson.error("no project is open"));
        }
        EngineSession.BundleParts p = parts.get();
        String stamp = STAMP.format(clock.instant().atZone(ZoneId.systemDefault()));
        Path file = body.get("path") instanceof String given && !given.isBlank()
                ? Path.of(given)
                : defaultFolder.resolve(p.project().name() + "-" + stamp + ".zip");
        // Every value that looks secret, from every service's settings: hidden wherever it shows up (a log line
        // printing a connection string, an error echoing a token).
        List<String> secrets = new ArrayList<>();
        p.environment()
                .values()
                .forEach(vars -> vars.forEach(v -> {
                    if (v.looksSecret()) {
                        secrets.add(v.value());
                    }
                }));
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (OutputStream out = Files.newOutputStream(file);
                    ZipOutputStream zip = new ZipOutputStream(out)) {
                put(zip, "README.txt", readme(p, stamp));
                put(zip, "devdesk.yaml", Redact.values(Redact.settings(p.devdeskYaml()), secrets));
                put(zip, "services.json", Redact.values(ApiJson.write(p.view()), secrets));
                put(zip, "findings.json", Redact.values(ApiJson.write(p.findings()), secrets));
                put(zip, "timeline.json", Redact.values(ApiJson.write(p.history()), secrets));
                put(zip, "settings.txt", settings(p));
                for (Map.Entry<String, List<String>> log : p.logs().entrySet()) {
                    put(
                            zip,
                            "logs/" + log.getKey() + ".log",
                            Redact.values(String.join("\n", log.getValue()), secrets));
                }
                EngineSession.Answer changed = changes.changes();
                put(zip, "what-changed.json", Redact.values(ApiJson.write(changed.body()), secrets));
                put(zip, "machine.txt", machine());
            }
            return new EngineSession.Answer(200, Map.of("path", file.toString(), "bytes", Files.size(file)));
        } catch (IOException e) {
            return new EngineSession.Answer(500, ApiJson.error("couldn't write " + file + ": " + e.getMessage()));
        }
    }

    private static String readme(EngineSession.BundleParts p, String stamp) {
        return "DevDesk crash bundle for \"" + p.project().name() + "\", made " + stamp + ".\n\n"
                + "services.json     each service's state right now\n"
                + "findings.json     what DevDesk's diagnosis found, with evidence\n"
                + "timeline.json     the last 24 hours of state changes, findings and warnings\n"
                + "logs/             the last 2000 lines each service printed\n"
                + "settings.txt      each service's settings: names and where they come from, never the values\n"
                + "what-changed.json what differs from the project's last clean start\n"
                + "devdesk.yaml      the project file\n"
                + "machine.txt       the operating system and memory\n\n"
                + "Values of secret-looking settings were replaced by *** everywhere. Still read it once before\n"
                + "sharing: a secret with an ordinary name can't be recognised.\n";
    }

    /** Names and sources only: "DB_URL (from .env)". Values never leave, secret or not. */
    private static String settings(EngineSession.BundleParts p) {
        StringBuilder sb = new StringBuilder();
        p.environment().forEach((service, vars) -> {
            sb.append(service).append('\n');
            for (ServiceEnvironment.Variable v : vars) {
                if (v.source() == ServiceEnvironment.Source.SHELL) {
                    continue; // the user's whole shell: noise, and private
                }
                sb.append("  ")
                        .append(v.key())
                        .append("  (")
                        .append(v.source().name().toLowerCase())
                        .append(")\n");
            }
        });
        return sb.toString();
    }

    private static String machine() {
        StringBuilder sb = new StringBuilder();
        sb.append("java ").append(System.getProperty("java.version")).append('\n');
        for (String f : List.of("/proc/version", "/etc/os-release")) {
            try {
                sb.append(Files.readString(Path.of(f))).append('\n');
            } catch (IOException e) {
                // not Linux, or not readable
            }
        }
        try {
            // The first lines of meminfo: total, free and available memory.
            Files.readAllLines(Path.of("/proc/meminfo")).stream()
                    .limit(3)
                    .forEach(l -> sb.append(l).append('\n'));
        } catch (IOException e) {
            // no memory figures
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
