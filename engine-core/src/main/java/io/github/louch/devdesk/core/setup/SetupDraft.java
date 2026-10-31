package io.github.louch.devdesk.core.setup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A project setup being edited in the "Add project" wizard, before it becomes a devdesk.yaml. Everything is plain text
 * exactly as the user will see it in the file: {@code ${VAR}} stays unresolved, so secrets from .env never get copied.
 *
 * @param envFiles .env files every service gets, relative to the project folder
 * @param notes things the scan could not do on its own, in plain words ("api is built from a Dockerfile…")
 * @param kept sections of an existing devdesk.yaml the wizard doesn't edit ({@code profiles:}, {@code tasks:}), as written, so saving
 *     from "Edit setup" never drops them
 */
public record SetupDraft(String name, List<String> envFiles, List<Service> services, List<String> notes, String kept) {

    public SetupDraft {
        envFiles = List.copyOf(envFiles);
        services = List.copyOf(services);
        notes = List.copyOf(notes);
    }

    /** A new draft: nothing to keep from an earlier file. */
    public SetupDraft(String name, List<String> envFiles, List<Service> services, List<String> notes) {
        this(name, envFiles, services, notes, "");
    }

    /**
     * One service card.
     *
     * @param kind {@code command} (runs on this machine) or {@code container}
     * @param command the start command; for a container, an optional override of the image's own command
     * @param directory where a command runs, relative to the project folder ("" = the folder itself)
     * @param ports a command's ports ("3000"), or a container's "host:container" mappings ("55432:5432")
     * @param volumes a container's "name:/path" or "./host/path:/path"
     * @param dependsOn other service → started, healthy or completed_successfully
     * @param origin where the scan found it, shown on the card ("docker-compose.yml", "api/package.json")
     * @param notes problems with this one service, shown on its card
     * @param extras settings the cards don't show but an existing devdesk.yaml had ("stop_grace_period",
     *     "health.interval"…): written back unchanged, so "Edit setup" never loses them
     */
    public record Service(
            String name,
            String kind,
            String command,
            String directory,
            String image,
            List<String> ports,
            List<String> volumes,
            Map<String, String> environment,
            Map<String, String> dependsOn,
            Health health,
            String restart,
            String origin,
            List<String> notes,
            Map<String, String> extras) {

        public Service {
            extras = new LinkedHashMap<>(extras);
            ports = List.copyOf(ports);
            volumes = List.copyOf(volumes);
            // Insertion order is the order the user wrote them: keep it in the file too.
            environment = new LinkedHashMap<>(environment);
            dependsOn = new LinkedHashMap<>(dependsOn);
            notes = List.copyOf(notes);
        }

        /** A new card: nothing extra to keep. */
        public Service(
                String name,
                String kind,
                String command,
                String directory,
                String image,
                List<String> ports,
                List<String> volumes,
                Map<String, String> environment,
                Map<String, String> dependsOn,
                Health health,
                String restart,
                String origin,
                List<String> notes) {
            this(
                    name,
                    kind,
                    command,
                    directory,
                    image,
                    ports,
                    volumes,
                    environment,
                    dependsOn,
                    health,
                    restart,
                    origin,
                    notes,
                    Map.of());
        }

        /** True for a container service, false for a command that runs on this machine. */
        public boolean isContainer() {
            return "container".equals(kind);
        }

        /** The host ports this service listens on: a command's own, or the host side of a container's mappings. */
        public List<Integer> hostPorts() {
            return ports.stream()
                    .map(p -> {
                        String[] parts = p.strip().split(":");
                        // "5432", "55432:5432" or "127.0.0.1:55432:5432": the host port is next to last (or alone)
                        String host = parts.length == 1 ? parts[0] : parts[parts.length - 2];
                        return host.matches("\\d{1,5}") ? Integer.parseInt(host) : -1;
                    })
                    .filter(p -> p > 0)
                    .toList();
        }

        /**
         * The same service run the other way: {@code command} (on this machine) or {@code container}. What the card
         * had is kept in {@code extras} ("container.command", "native.env.DB_HOST"…), and what the other way needs
         * comes from there: for a service compose builds, the scan put the command that runs it here.
         */
        public Service switchTo(String next) {
            if (next.equals(kind) || !(next.equals("command") || next.equals("container"))) {
                return this;
            }
            String from = isContainer() ? "container" : "native";
            String to = next.equals("container") ? "container" : "native";
            Map<String, String> x = new LinkedHashMap<>(extras);
            x.keySet().removeIf(k -> k.startsWith(from + "."));
            x.put(from + ".command", command);
            x.put(from + ".directory", directory);
            x.put(from + ".ports", String.join(",", ports));
            x.put(from + ".health.type", health.type());
            x.put(from + ".health.target", health.target());
            environment.forEach((k, v) -> x.put(from + ".env." + k, v));

            Map<String, String> env = new LinkedHashMap<>(environment);
            x.forEach((k, v) -> {
                if (k.startsWith(to + ".env.")) {
                    env.put(k.substring((to + ".env.").length()), v);
                }
            });
            List<String> nextPorts = x.containsKey(to + ".ports")
                    ? java.util.Arrays.stream(x.get(to + ".ports").split(","))
                            .filter(p -> !p.isBlank())
                            .toList()
                    : hostPorts().stream()
                            .map(p -> next.equals("container") ? p + ":" + p : String.valueOf(p))
                            .toList();
            Health nextHealth = x.containsKey(to + ".health.type")
                    ? new Health(x.get(to + ".health.type"), x.getOrDefault(to + ".health.target", ""), "")
                    : next.equals("command") && health.type().equals("docker") ? Health.PROCESS : health;
            return new Service(
                    name,
                    next,
                    x.getOrDefault(to + ".command", ""),
                    x.getOrDefault(to + ".directory", ""),
                    image,
                    nextPorts,
                    volumes,
                    env,
                    dependsOn,
                    nextHealth,
                    restart,
                    origin,
                    notes,
                    x);
        }

        /** The same service with its depends_on replaced by {@code next}. */
        public Service withDependsOn(Map<String, String> next) {
            return new Service(
                    name,
                    kind,
                    command,
                    directory,
                    image,
                    ports,
                    volumes,
                    environment,
                    next,
                    health,
                    restart,
                    origin,
                    notes,
                    extras);
        }
    }

    /**
     * How DevDesk knows the service is ready.
     *
     * @param type {@code process} (just running), {@code tcp}, {@code http}, {@code command} or {@code docker}
     * @param target the port (tcp), the URL (http) or the command (command); empty otherwise
     * @param startPeriod how long it may take to start, like "40s"; empty = DevDesk's default
     */
    public record Health(String type, String target, String startPeriod) {

        public static final Health PROCESS = new Health("process", "", "");

        /** A check that passes once something accepts connections on {@code port}. */
        public static Health tcp(int port) {
            return new Health("tcp", String.valueOf(port), "");
        }

        /** A check that passes once {@code url} answers an HTTP GET with a status from 200 to 399. */
        public static Health http(String url) {
            return new Health("http", url, "");
        }

        /** A check that passes once {@code command} exits with 0; failures during {@code startPeriod} don't count. */
        public static Health command(String command, String startPeriod) {
            return new Health("command", command, startPeriod);
        }
    }
}
