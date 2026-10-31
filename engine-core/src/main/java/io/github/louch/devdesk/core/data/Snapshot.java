package io.github.louch.devdesk.core.data;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A named "save game" of the project's data: every database's full dump, every Redis's keys, and the env files, taken
 * together so they can be put back together ("before-migration").
 *
 * @param id the folder name: time plus label, e.g. {@code 2026-10-13_14-02-11_before-migration}
 * @param items what was saved, one per database, Redis or env file, including what was skipped and why
 */
public record Snapshot(String id, String label, Instant created, List<Item> items) {

    public Snapshot {
        items = List.copyOf(items);
    }

    /** What one part of a snapshot holds. */
    public enum Kind {
        /** A whole SQL or MongoDB database, saved by its own tool (pg_dump, mysqldump, mongodump) or a file copy. */
        DATABASE,
        /** Every key of a Redis, with its type and time to live. */
        REDIS,
        /** An env file of the project folder (.env, .env.local): settings that go with the data. */
        SETTINGS
    }

    /**
     * One part.
     *
     * @param name the service ("db"), or the env file's name (".env")
     * @param file the file inside the snapshot's folder; empty when nothing was saved
     * @param problem why it wasn't saved (not running, the tool failed), or a warning; empty when all went well
     */
    public record Item(Kind kind, String name, Optional<String> file, long bytes, Optional<String> problem) {

        /**
         * True when this item was written to a file; false when it was skipped or failed (then {@code problem} says
         * why).
         */
        public boolean saved() {
            return file.isPresent();
        }
    }

    /** "Before Migration!" → "before-migration": safe in a folder name, still readable. Empty if nothing is left. */
    public static String slug(String label) {
        String s = label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return s.length() > 40 ? s.substring(0, 40).replaceAll("-$", "") : s;
    }

    /** True if every part was saved. A partial snapshot can still be restored: the missing parts stay as they are. */
    public boolean complete() {
        return items.stream().allMatch(Item::saved);
    }
}
