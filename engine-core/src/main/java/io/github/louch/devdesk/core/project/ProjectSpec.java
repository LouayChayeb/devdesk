package io.github.louch.devdesk.core.project;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A project as written in devdesk.yaml, fully validated.
 *
 * @param root the folder containing devdesk.yaml; service directories are relative to it
 * @param environment variables from the env_files, given to every service (a service's own environment wins)
 * @param services by name, in file order
 * @param profiles the setting overrides one can switch to ("demo", "test"), by name
 * @param tasks one-off commands (seed, test, reset-db), by name, in file order
 */
public record ProjectSpec(
        String name,
        Path root,
        Map<String, String> environment,
        Map<String, ServiceSpec> services,
        Map<String, Profile> profiles,
        Map<String, TaskSpec> tasks,
        List<String> warnings) {

    /** A project with nothing to warn about. */
    public ProjectSpec(
            String name,
            Path root,
            Map<String, String> environment,
            Map<String, ServiceSpec> services,
            Map<String, Profile> profiles,
            Map<String, TaskSpec> tasks) {
        this(name, root, environment, services, profiles, tasks, List.of());
    }

    /** A project without profiles. */
    public ProjectSpec(String name, Path root, Map<String, String> environment, Map<String, ServiceSpec> services) {
        this(name, root, environment, services, Map.of(), Map.of());
    }

    /**
     * A short stable id for this project on this machine: its name plus a hash of its folder, so two projects called
     * "api" in different folders never mix (history, leftovers, locks). E.g. "three-tier-70fadd7e".
     */
    public String key() {
        return keyOf(name, root);
    }

    /** The key a project with this name in this folder gets, before its devdesk.yaml is loaded (Set up's downloads). */
    public static String keyOf(String name, Path root) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(root.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return name.replaceAll("[^A-Za-z0-9_.-]", "_") + "-"
                    + HexFormat.of().formatHex(hash, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }

    /**
     * The values are copied, so a project never changes after it's read. {@code warnings}: what's allowed but probably
     * not meant, said when the project opens ("${BASE_URL} isn't set, so it's empty"), like compose's warnings.
     */
    public ProjectSpec {
        warnings = List.copyOf(warnings);
        environment = Map.copyOf(environment);
        // LinkedHashMap keeps file order, so output lists services the way the user wrote them.
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
        profiles = Collections.unmodifiableMap(new LinkedHashMap<>(profiles));
        tasks = Collections.unmodifiableMap(new LinkedHashMap<>(tasks));
    }
}
