package io.github.louch.devdesk.core.setup;

import io.github.louch.devdesk.core.data.DatabaseTargets;
import io.github.louch.devdesk.core.mail.MailCatchers;
import java.util.List;
import java.util.Optional;

/**
 * Health checks for images DevDesk recognises, when a compose file has none. A TCP check would pass too early: Docker's
 * port forwarder accepts connections before the database inside is ready, so these ask the database itself.
 */
final class KnownImages {

    private KnownImages() {}

    /**
     * @param container the container's name, for {@code docker exec}
     * @param ports the card's "host:container" mappings (Mailpit's check uses its web port)
     */
    static Optional<SetupDraft.Health> health(String image, String container, List<String> ports) {
        String exec = "docker exec " + container + " ";
        if (DatabaseTargets.isPostgresImage(image)) {
            return Optional.of(SetupDraft.Health.command(exec + "pg_isready -h 127.0.0.1", "40s"));
        }
        if (DatabaseTargets.isMysqlImage(image)) {
            // mysqladmin answers "mysqld is alive" even when the password is wrong: enough to know it's up.
            return Optional.of(SetupDraft.Health.command(exec + "mysqladmin ping -h 127.0.0.1 --silent", "60s"));
        }
        if (DatabaseTargets.isRedisImage(image)) {
            return Optional.of(SetupDraft.Health.command(exec + "redis-cli ping", "20s"));
        }
        if (DatabaseTargets.isMongoImage(image)) {
            return Optional.of(
                    SetupDraft.Health.command(exec + "mongosh --quiet --eval \"db.adminCommand('ping')\"", "40s"));
        }
        if (MailCatchers.isMailpitImage(image)) {
            return ports.stream()
                    .filter(p -> p.endsWith(":8025"))
                    .findFirst()
                    .map(p -> p.split(":"))
                    .map(parts -> new SetupDraft.Health(
                            "http", "http://127.0.0.1:" + parts[parts.length - 2] + "/readyz", "20s"));
        }
        return Optional.empty();
    }
}
