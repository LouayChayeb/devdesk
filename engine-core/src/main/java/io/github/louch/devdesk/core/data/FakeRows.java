package io.github.louch.devdesk.core.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Made-up rows for a table ("fill with 50 test rows"): each column gets values that fit its name first (email,
 * first_name, price, created_at), else its type. Columns the database fills itself (ids, defaults) are left to it,
 * and a foreign key only points at rows that exist. Random and time are injected, so tests are repeatable.
 */
public final class FakeRows {

    /** How one column is filled. {@code how} is said to the user: "an email", "left to the database (nextval…)". */
    public record Fill(String column, String how, boolean generated) {}

    /**
     * What's needed to plan a table.
     *
     * @param references for each single-column foreign key: some values that exist in the table it points at
     * @param maxIds for integer primary keys without a default: the largest id there now, so new ids follow it
     */
    public record Input(
            SqlBrowser.Structure structure,
            DatabaseTarget.Kind kind,
            Map<String, List<String>> references,
            Map<String, Long> maxIds) {}

    private static final List<String> FIRST = List.of(
            "Amira", "Ben", "Chloé", "Dmitri", "Elena", "Farid", "Grace", "Hugo", "Ines", "Jonas", "Keiko", "Liam",
            "Maya", "Noah", "Olga", "Pablo", "Quentin", "Rania", "Sofia", "Tariq", "Uma", "Victor", "Wen", "Yasmin",
            "Zoe");
    private static final List<String> LAST = List.of(
            "Ali",
            "Becker",
            "Chen",
            "Dubois",
            "Evans",
            "Fischer",
            "García",
            "Haddad",
            "Ivanova",
            "Jensen",
            "Kim",
            "Lopez",
            "Martin",
            "Nakamura",
            "Okafor",
            "Petrov",
            "Rossi",
            "Silva",
            "Tanaka",
            "Weber");
    private static final List<String> CITIES =
            List.of("Lisbon", "Tunis", "Berlin", "Osaka", "Toronto", "Lyon", "Nairobi", "Seoul", "Porto", "Krakow");
    private static final List<String> COUNTRIES =
            List.of("Portugal", "Tunisia", "Germany", "Japan", "Canada", "France", "Kenya", "South Korea");
    private static final List<String> COUNTRY_CODES = List.of("PT", "TN", "DE", "JP", "CA", "FR", "KE", "KR");
    private static final List<String> STREETS = List.of("Oak", "Maple", "Harbor", "Station", "Mill", "Garden", "Park");
    private static final List<String> PRODUCTS = List.of(
            "Desk lamp",
            "Notebook",
            "Coffee mug",
            "Backpack",
            "Headphones",
            "Water bottle",
            "Keyboard",
            "Plant pot",
            "Phone case",
            "Running shoes");
    private static final List<String> WORDS = List.of(
            "quick", "blue", "order", "garden", "window", "paper", "river", "silver", "morning", "table", "light",
            "music", "travel", "bright", "simple", "market", "green", "stone", "cloud", "story");
    private static final List<String> STATUSES = List.of("active", "pending", "inactive");
    private static final List<String> CURRENCIES = List.of("EUR", "USD", "GBP", "JPY", "TND");

    private static final Pattern LENGTH = Pattern.compile("\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)");
    private static final Pattern ENUM_VALUES = Pattern.compile("'((?:[^']|'')*)'");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Input input;
    private final RandomGenerator random;
    private final Clock clock;
    private final Map<String, Generator> generators = new LinkedHashMap<>();
    private final List<Fill> fills = new ArrayList<>();
    private final Map<String, Set<String>> used = new LinkedHashMap<>();
    private final Map<String, Long> nextId = new LinkedHashMap<>();
    private long counter;
    /** The made-up person of the current row: its name, email and user name all belong to them. */
    private String first = "";

    private String last = "";

    /** Makes one value; {@code n} counts rows from 1, for values that must be unique. Empty = SQL NULL. */
    @FunctionalInterface
    private interface Generator {
        Optional<String> next(long n);
    }

    public FakeRows(Input input, RandomGenerator random, Clock clock) {
        this.input = input;
        this.random = random;
        this.clock = clock;
        Set<String> unique = new HashSet<>();
        input.structure().indexes().stream()
                .filter(i -> i.unique() && i.columns().size() == 1)
                .forEach(i -> unique.add(i.columns().getFirst()));
        Map<String, SqlBrowser.ForeignKey> links = new LinkedHashMap<>();
        input.structure().foreignKeys().forEach(f -> f.columns().forEach(c -> links.put(c, f)));
        long primaryColumns = input.structure().columns().stream()
                .filter(SqlBrowser.Column::primaryKey)
                .count();
        for (SqlBrowser.Column c : input.structure().columns()) {
            plan(
                    c,
                    unique.contains(c.name()) || (c.primaryKey() && primaryColumns == 1),
                    links.get(c.name()),
                    primaryColumns);
        }
    }

    /** How each column will be filled, for the user to read before anything is written. */
    public List<Fill> plan() {
        return List.copyOf(fills);
    }

    /** Why it can't be done (a required link to an empty table); empty when rows can be made. */
    public Optional<String> problem() {
        return fills.stream()
                .filter(f -> f.how().startsWith("can't"))
                .map(f -> f.column() + ": " + f.how())
                .reduce((a, b) -> a + "; " + b);
    }

    /** The next {@code count} rows: generated columns only; the others get the database's default. */
    public List<Map<String, Optional<String>>> rows(int count) {
        List<Map<String, Optional<String>>> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            counter++;
            first = pick(FIRST);
            last = pick(LAST);
            Map<String, Optional<String>> row = new LinkedHashMap<>();
            generators.forEach((column, g) -> row.put(column, g.next(counter)));
            out.add(row);
        }
        return out;
    }

    private void plan(SqlBrowser.Column c, boolean unique, SqlBrowser.ForeignKey link, long primaryColumns) {
        String type = c.type().toLowerCase(Locale.ROOT);
        if (c.defaultValue().isPresent()) {
            skip(c, "left to the database (" + c.defaultValue().get() + ")");
            return;
        }
        if (link != null) {
            List<String> existing = input.references().getOrDefault(c.name(), List.of());
            String target =
                    link.toTable() + "." + link.toColumns().get(link.columns().indexOf(c.name()));
            if (link.columns().size() > 1) {
                if (c.nullable()) {
                    use(c, "empty (a link over several columns: DevDesk doesn't pick those)", n -> Optional.empty());
                } else {
                    skip(c, "can't fill a required link over several columns (to " + target + ")");
                }
            } else if (existing.isEmpty()) {
                if (c.nullable()) {
                    use(c, "empty: " + target + " has no rows to point at yet", n -> Optional.empty());
                } else {
                    skip(c, "can't: it must point at a row of " + link.toTable() + ", which is empty. Fill that first");
                }
            } else {
                use(c, "an existing " + target, n -> Optional.of(pick(existing)));
            }
            return;
        }
        if (c.primaryKey() && primaryColumns == 1 && isInteger(type)) {
            if (input.kind() == DatabaseTarget.Kind.SQLITE && type.equals("integer")) {
                skip(c, "left to SQLite (an INTEGER PRIMARY KEY numbers itself)");
                return;
            }
            nextId.put(c.name(), input.maxIds().getOrDefault(c.name(), 0L) + 1);
            use(c, "the next free ids", n -> Optional.of(Long.toString(nextId.merge(c.name(), 1L, Long::sum) - 1)));
            return;
        }
        Optional<Named> byName = byName(c.name().toLowerCase(Locale.ROOT), type);
        Generator g;
        String how;
        if (byName.isPresent()) {
            g = byName.get().generator();
            how = byName.get().how();
        } else {
            Optional<Generator> byType = byType(type);
            if (byType.isEmpty()) {
                if (c.nullable()) {
                    use(c, "empty (DevDesk doesn't know how to make a " + c.type() + ")", n -> Optional.empty());
                } else {
                    skip(c, "can't make a " + c.type() + ": add a default to the column, or fill it by hand");
                }
                return;
            }
            g = byType.get();
            how = "random " + c.type();
        }
        Generator sized = fit(g, type);
        use(c, how + (unique ? ", unique" : ""), unique ? unique(c.name(), sized) : sized);
    }

    private void use(SqlBrowser.Column c, String how, Generator g) {
        generators.put(c.name(), g);
        fills.add(new Fill(c.name(), how, true));
    }

    private void skip(SqlBrowser.Column c, String how) {
        fills.add(new Fill(c.name(), how, false));
    }

    // ---- by the column's name ----

    private Optional<Named> byName(String name, String type) {
        boolean text = isText(type);
        boolean time = type.contains("date") || type.contains("time");
        if (time) {
            if (name.contains("birth") || name.equals("dob")) {
                return named("a birth date", n -> Optional.of(date(type, randomDate(1950, 2006))));
            }
            return Optional.empty(); // other dates: the type decides (within the last year)
        }
        if (!text) {
            if (isNumber(type)
                    && (name.contains("price")
                            || name.contains("amount")
                            || name.contains("total")
                            || name.contains("cost")
                            || name.contains("balance"))) {
                return named("an amount (1–500)", n -> Optional.of(decimal(1, 500, scale(type, 2))));
            }
            if (isInteger(type) && (name.contains("quantity") || name.equals("qty") || name.contains("stock"))) {
                return named("a quantity (1–100)", n -> Optional.of(Integer.toString(random.nextInt(1, 101))));
            }
            if (isInteger(type) && name.equals("age")) {
                return named("an age (18–80)", n -> Optional.of(Integer.toString(random.nextInt(18, 81))));
            }
            return Optional.empty();
        }
        if (name.contains("email")) {
            return named(
                    "an email (@example.com)", n -> Optional.of(ascii(first) + "." + ascii(last) + n + "@example.com"));
        }
        if (name.matches(".*(first|given).?name.*")) {
            return named("a first name", n -> Optional.of(first));
        }
        if (name.matches(".*(last|family|sur).?name.*")) {
            return named("a last name", n -> Optional.of(last));
        }
        if (name.matches(".*(user|login|nick).?name.*") || name.equals("login") || name.equals("handle")) {
            return named("a user name", n -> Optional.of(ascii(first) + n));
        }
        if (name.equals("name") || name.matches(".*(full|display|contact|customer|author).?name.*")) {
            return named("a full name", n -> Optional.of(first + " " + last));
        }
        if (name.contains("phone") || name.contains("mobile") || name.equals("tel")) {
            // 555-01xx numbers are reserved for fiction: they never ring anyone.
            return named(
                    "a phone number (555-01xx)",
                    n -> Optional.of("+1 555 01" + String.format("%02d", random.nextInt(100))));
        }
        if (name.contains("country") && (name.contains("code") || type.matches(".*\\(2\\).*"))) {
            return named("a country code", n -> Optional.of(pick(COUNTRY_CODES)));
        }
        if (name.contains("country")) {
            return named("a country", n -> Optional.of(pick(COUNTRIES)));
        }
        if (name.contains("city") || name.contains("town")) {
            return named("a city", n -> Optional.of(pick(CITIES)));
        }
        if (name.contains("zip") || name.contains("postal") || name.contains("postcode")) {
            return named("a postal code", n -> Optional.of(String.format("%05d", random.nextInt(100_000))));
        }
        if (name.contains("address") || name.contains("street")) {
            return named(
                    "a street address", n -> Optional.of(random.nextInt(1, 200) + " " + pick(STREETS) + " Street"));
        }
        if (name.contains("avatar") || name.contains("image") || name.contains("photo") || name.contains("picture")) {
            return named("an image URL", n -> Optional.of("https://picsum.photos/seed/" + n + "/200"));
        }
        if (name.contains("url") || name.contains("website") || name.contains("link")) {
            return named("a URL", n -> Optional.of("https://example.com/" + pick(WORDS) + "/" + n));
        }
        if (name.contains("uuid") || name.contains("guid")) {
            return named("a UUID", n -> Optional.of(uuid()));
        }
        if (name.contains("slug")) {
            return named("a slug", n -> Optional.of(pick(WORDS) + "-" + pick(WORDS) + "-" + n));
        }
        if (name.contains("password") || name.contains("hash")) {
            // Not a working password: a fixed fake hash, so nobody can log in with test rows by accident.
            return named(
                    "a fake password hash",
                    n -> Optional.of("$2b$10$devdesk.fake.hash.not.a.real.password.000000000000"));
        }
        if (name.contains("token") || name.contains("secret")) {
            return named("a random token", n -> Optional.of(uuid().replace("-", "")));
        }
        if (name.equals("ip") || name.contains("ip_address")) {
            return named(
                    "an IP address (10.x.x.x)",
                    n -> Optional.of(
                            "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255)));
        }
        if (name.contains("currency")) {
            return named("a currency code", n -> Optional.of(pick(CURRENCIES)));
        }
        if (name.equals("status") || name.equals("state")) {
            return named("a status (active, pending, inactive)", n -> Optional.of(pick(STATUSES)));
        }
        if (name.contains("product") || name.equals("item") || name.equals("title")) {
            return named("a product name", n -> Optional.of(pick(PRODUCTS)));
        }
        if (name.contains("description")
                || name.contains("bio")
                || name.contains("body")
                || name.contains("content")
                || name.contains("comment")
                || name.contains("note")
                || name.contains("message")) {
            return named("a sentence", n -> Optional.of(sentence(8 + random.nextInt(10))));
        }
        return Optional.empty();
    }

    /** A rule's value maker and how it's described to the user. */
    private record Named(String how, Generator generator) {}

    private static Optional<Named> named(String how, Generator g) {
        return Optional.of(new Named(how, g));
    }

    // ---- by the column's type ----

    private Optional<Generator> byType(String type) {
        Matcher enumValues = ENUM_VALUES.matcher(type);
        if (type.startsWith("enum(") || type.startsWith("set(")) {
            List<String> values = new ArrayList<>();
            while (enumValues.find()) {
                values.add(enumValues.group(1).replace("''", "'"));
            }
            return values.isEmpty() ? Optional.empty() : Optional.of(n -> Optional.of(pick(values)));
        }
        if (type.equals("tinyint(1)") || type.startsWith("bool")) {
            boolean digits = type.startsWith("tinyint") || input.kind() == DatabaseTarget.Kind.SQLITE;
            return Optional.of(
                    n -> Optional.of(random.nextBoolean() ? (digits ? "1" : "true") : (digits ? "0" : "false")));
        }
        if (isInteger(type)) {
            int max = type.startsWith("smallint") || type.startsWith("int2")
                    ? 30_000
                    : type.startsWith("tinyint") ? 100 : 1000;
            return Optional.of(n -> Optional.of(Integer.toString(random.nextInt(1, max + 1))));
        }
        if (isNumber(type)) {
            return Optional.of(n -> Optional.of(decimal(0, 1000, scale(type, 2))));
        }
        if (type.startsWith("timestamp") || type.startsWith("datetime")) {
            return Optional.of(n -> Optional.of(TIMESTAMP.format(recentTime())));
        }
        if (type.startsWith("date")) {
            return Optional.of(n -> Optional.of(recentTime().toLocalDate().toString()));
        }
        if (type.startsWith("time")) {
            return Optional.of(n -> Optional.of(String.format("%02d:%02d:00", random.nextInt(24), random.nextInt(60))));
        }
        if (type.equals("uuid")) {
            return Optional.of(n -> Optional.of(uuid()));
        }
        if (type.startsWith("json")) {
            return Optional.of(
                    n -> Optional.of("{\"" + pick(WORDS) + "\": \"" + pick(WORDS) + "\", \"n\": " + n + "}"));
        }
        if (type.endsWith("[]")) {
            return Optional.of(n -> Optional.of("{}")); // an empty Postgres array
        }
        if (isText(type)) {
            return Optional.of(n -> Optional.of(pick(WORDS) + " " + pick(WORDS)));
        }
        return Optional.empty();
    }

    // ---- helpers ----

    /** Text cut to the column's length: varchar(20) never gets 30 characters. */
    private static Generator fit(Generator g, String type) {
        Matcher m = LENGTH.matcher(type);
        if (!isText(type) || !m.find()) {
            return g;
        }
        int max = Integer.parseInt(m.group(1));
        return n -> g.next(n).map(v -> v.length() > max ? v.substring(0, max) : v);
    }

    /** A value already used in this column gets the row number added, so unique indexes never refuse a row. */
    private Generator unique(String column, Generator g) {
        Set<String> seen = used.computeIfAbsent(column, c -> new HashSet<>());
        return n -> g.next(n).map(v -> seen.add(v) ? v : v + "-" + n);
    }

    private static boolean isInteger(String type) {
        return type.matches(
                "(small|big|tiny|medium)?int(eger)?\\d*( unsigned)?(\\(\\d+\\))?( unsigned)?|(big|small)?serial\\d*");
    }

    private static boolean isNumber(String type) {
        return isInteger(type) || type.matches("(numeric|decimal|real|double( precision)?|float\\d*|money).*");
    }

    private static boolean isText(String type) {
        return type.matches("(character varying|varchar|character|char|nchar|nvarchar|text|tinytext|mediumtext"
                + "|longtext|string|citext|clob|any).*");
    }

    private static int scale(String type, int fallback) {
        Matcher m = LENGTH.matcher(type);
        return m.find() && m.group(2) != null ? Integer.parseInt(m.group(2)) : fallback;
    }

    private String decimal(int min, int max, int scale) {
        return BigDecimal.valueOf(min + random.nextDouble() * (max - min))
                .setScale(scale, RoundingMode.HALF_UP)
                .toPlainString();
    }

    /** Somewhere in the last year: test data that looks recent. */
    private LocalDateTime recentTime() {
        Instant now = clock.instant();
        Instant t = now.minus(Duration.ofSeconds(random.nextLong(365L * 24 * 3600)));
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC).withNano(0);
    }

    private LocalDate randomDate(int fromYear, int toYear) {
        long from = LocalDate.of(fromYear, 1, 1).toEpochDay();
        long to = LocalDate.of(toYear, 12, 31).toEpochDay();
        return LocalDate.ofEpochDay(from + random.nextLong(to - from));
    }

    private String date(String type, LocalDate day) {
        return type.startsWith("timestamp") || type.startsWith("datetime") ? day + " 00:00:00" : day.toString();
    }

    private String sentence(int words) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < words; i++) {
            s.append(i == 0 ? "" : " ").append(pick(WORDS));
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1) + ".";
    }

    /** "Chloé" → "chloe": accents taken off (é is e plus an accent mark in Unicode's split form), then a-z only. */
    private static String ascii(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z]", "");
    }

    private String uuid() {
        return new UUID(random.nextLong(), random.nextLong()).toString();
    }

    private <T> T pick(List<T> from) {
        return from.get(random.nextInt(from.size()));
    }
}
