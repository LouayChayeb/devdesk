package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import io.github.louch.devdesk.core.data.KeyValueBrowser.Edit;
import io.github.louch.devdesk.core.data.KeyValueBrowser.Field;
import io.github.louch.devdesk.core.data.KeyValueBrowser.KeyInfo;
import io.github.louch.devdesk.core.data.KeyValueBrowser.Member;
import io.github.louch.devdesk.core.data.KeyValueBrowser.Value;
import io.github.louch.devdesk.core.data.KeyValueBrowser.WatchEvent;
import io.github.louch.devdesk.core.data.KeyValueBrowser.WatchKind;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Against a real Redis (with a password) in a throwaway container. Skipped when Docker isn't available. */
class RedisBrowserTest {

    private static final String NAME =
            "devdesk-test-redis-" + ProcessHandle.current().pid();
    private static DatabaseTarget redis;
    private final RedisBrowser browser = new RedisBrowser();

    @BeforeAll
    static void startRedis() throws Exception {
        assumeTrue(run("docker", "info").isPresent(), "Docker is not available");
        run(
                        "docker",
                        "run",
                        "-d",
                        "--rm",
                        "--name",
                        NAME,
                        "-p",
                        "127.0.0.1::6379",
                        "redis:8",
                        "redis-server",
                        "--requirepass",
                        "dev")
                .orElseThrow();
        String mapped = run("docker", "port", NAME, "6379/tcp").orElseThrow().strip();
        int port = Integer.parseInt(mapped.substring(mapped.lastIndexOf(':') + 1));
        redis = new DatabaseTarget("cache", DatabaseTarget.Kind.REDIS, "127.0.0.1", port, "", "dev", "0");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            try {
                cmd("PING");
                break;
            } catch (DataException e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(300);
            }
        }
        cmd("SET", "user:1:name", "Ada");
        cmd("SET", "user:2:name", "Bob", "EX", "300");
        cmd("RPUSH", "jobs", "a", "b", "c");
        cmd("SADD", "tags", "x", "z", "y");
        cmd("HSET", "user:1", "name", "Ada", "role", "admin");
        cmd("ZADD", "scores", "2", "bob", "1", "ada");
        cmd("XADD", "events", "*", "type", "signup");
    }

    @AfterAll
    static void stopRedis() {
        run("docker", "rm", "-f", "-v", NAME);
    }

    @Test
    void streamGroupsShowPendingMessagesAndIdleConsumers() {
        browser.edit(
                redis, "audit:log", new Edit.StreamAdd(List.of(new Field("type", "paid"), new Field("order", "7"))));
        browser.edit(redis, "audit:log", new Edit.StreamAdd(List.of(new Field("type", "shipped"))));
        browser.edit(redis, "audit:log", new Edit.GroupCreate("mailer", true));
        cmd("XREADGROUP", "GROUP", "mailer", "worker-1", "COUNT", "1", "STREAMS", "audit:log", ">");

        var info = browser.stream(redis, "audit:log");

        assertThat(info.length()).isEqualTo(2);
        assertThat(info.groups()).singleElement().satisfies(g -> {
            assertThat(g.name()).isEqualTo("mailer");
            assertThat(g.pending()).isEqualTo(1);
            assertThat(g.lag()).isEqualTo(1);
            assertThat(g.consumers()).singleElement().satisfies(c -> {
                assertThat(c.name()).isEqualTo("worker-1");
                assertThat(c.pending()).isEqualTo(1);
            });
        });
        browser.edit(redis, "audit:log", new Edit.GroupDelete("mailer"));
        assertThat(browser.stream(redis, "audit:log").groups()).isEmpty();
        assertThatThrownBy(() -> browser.stream(redis, "user:1:name")).isInstanceOf(DataException.class);
    }

    @Test
    void serverStatsComeFromInfo() {
        var s = browser.server(redis);

        assertThat(s.version()).startsWith("8.");
        assertThat(s.clients()).isPositive();
        assertThat(s.usedBytes()).isPositive();
        assertThat(s.keys()).isPositive();
    }

    @Test
    void dumpedKeysComeBackExactlyWithTheirTtl() {
        cmd("HSET", "copy:h", "a", "1", "b", "\u00e9t\u00e9");
        cmd("SET", "copy:s", "x", "EX", "500");
        var dumped = browser.dump(redis, "copy:*", 100);
        assertThat(dumped).extracting(d -> d.key()).containsExactlyInAnyOrder("copy:h", "copy:s");
        cmd("DEL", "copy:h");

        assertThat(browser.restore(redis, dumped, false)).isEqualTo(1); // copy:s still exists: skipped
        assertThat(cmd("HGET", "copy:h", "b")).isEqualTo("\"\u00e9t\u00e9\"");
        assertThat(browser.restore(redis, dumped, true)).isEqualTo(2);
        assertThat(cmd("TTL", "copy:s")).matches("\\(integer\\) [1-5]\\d\\d");
    }

    @Test
    void keysAreWalkedWithTypeAndTtl() {
        List<KeyInfo> all = new ArrayList<>();
        String cursor = "0";
        do {
            var page = browser.keys(redis, "user:*", cursor, 100);
            all.addAll(page.keys());
            cursor = page.next();
        } while (!cursor.equals("0"));

        assertThat(all).extracting(KeyInfo::key).containsExactlyInAnyOrder("user:1:name", "user:2:name", "user:1");
        assertThat(all)
                .filteredOn(k -> k.key().equals("user:1"))
                .singleElement()
                .satisfies(k -> {
                    assertThat(k.type()).isEqualTo("hash");
                    assertThat(k.ttl()).isEmpty();
                });
        assertThat(all)
                .filteredOn(k -> k.key().equals("user:2:name"))
                .singleElement()
                .satisfies(k ->
                        assertThat(k.ttl().orElseThrow()).isBetween(Duration.ofSeconds(200), Duration.ofSeconds(300)));
    }

    @Test
    void everyTypeIsRead() {
        assertThat(browser.read(redis, "user:1:name").orElseThrow().value()).isEqualTo(new Value.Text("Ada"));
        assertThat(browser.read(redis, "jobs").orElseThrow().value())
                .isEqualTo(new Value.Items(List.of("a", "b", "c")));
        assertThat(browser.read(redis, "tags").orElseThrow().value())
                .isEqualTo(new Value.Items(List.of("x", "y", "z")));
        assertThat(browser.read(redis, "user:1").orElseThrow().value())
                .isEqualTo(new Value.Fields(List.of(new Field("name", "Ada"), new Field("role", "admin"))));
        assertThat(browser.read(redis, "scores").orElseThrow().value())
                .isEqualTo(new Value.Scored(List.of(new Member("ada", 1), new Member("bob", 2))));
        var events = browser.read(redis, "events").orElseThrow();
        assertThat(events.length()).isEqualTo(1);
        assertThat(events.value())
                .isInstanceOfSatisfying(
                        Value.Entries.class,
                        e -> assertThat(e.entries().getFirst().fields()).containsExactly(new Field("type", "signup")));
        assertThat(browser.read(redis, "nope")).isEmpty();
    }

    @Test
    void ttlCanBeSetAndRemovedAndKeysDeleted() {
        cmd("SET", "temp", "1");
        assertThat(browser.expire(redis, "temp", Optional.of(Duration.ofSeconds(60))))
                .isTrue();
        assertThat(browser.read(redis, "temp").orElseThrow().ttl()).isPresent();
        assertThat(browser.expire(redis, "temp", Optional.empty())).isTrue();
        assertThat(browser.read(redis, "temp").orElseThrow().ttl()).isEmpty();

        assertThat(browser.delete(redis, "temp")).isTrue();
        assertThat(browser.delete(redis, "temp")).isFalse();
        assertThat(browser.expire(redis, "temp", Optional.empty())).isFalse();
    }

    @Test
    void valuesOfEveryTypeAreEdited() {
        browser.edit(redis, "e:str", new Edit.SetText("one"));
        cmd("EXPIRE", "e:str", "100");
        browser.edit(redis, "e:str", new Edit.SetText("two"));
        var str = browser.read(redis, "e:str").orElseThrow();
        assertThat(str.value()).isEqualTo(new Value.Text("two"));
        assertThat(str.ttl()).isPresent(); // KEEPTTL

        browser.edit(redis, "e:hash", new Edit.HashSet("a", "1"));
        browser.edit(redis, "e:hash", new Edit.HashSet("b", "2"));
        browser.edit(redis, "e:hash", new Edit.HashDelete("a"));
        assertThat(browser.read(redis, "e:hash").orElseThrow().value())
                .isEqualTo(new Value.Fields(List.of(new Field("b", "2"))));

        browser.edit(redis, "e:list", new Edit.ListPush("b", false));
        browser.edit(redis, "e:list", new Edit.ListPush("a", true));
        browser.edit(redis, "e:list", new Edit.ListPush("c", false));
        browser.edit(redis, "e:list", new Edit.ListSet(2, "C"));
        browser.edit(redis, "e:list", new Edit.ListRemove(1));
        assertThat(browser.read(redis, "e:list").orElseThrow().value()).isEqualTo(new Value.Items(List.of("a", "C")));

        browser.edit(redis, "e:set", new Edit.SetAdd("x"));
        browser.edit(redis, "e:set", new Edit.SetAdd("y"));
        browser.edit(redis, "e:set", new Edit.SetRemove("x"));
        assertThat(browser.read(redis, "e:set").orElseThrow().value()).isEqualTo(new Value.Items(List.of("y")));

        browser.edit(redis, "e:z", new Edit.ScoreSet("ada", 3));
        browser.edit(redis, "e:z", new Edit.ScoreSet("bob", 1));
        browser.edit(redis, "e:z", new Edit.ScoreRemove("ada"));
        assertThat(browser.read(redis, "e:z").orElseThrow().value())
                .isEqualTo(new Value.Scored(List.of(new Member("bob", 1))));
    }

    @Test
    void aFailedEditSaysWhy() {
        cmd("RPUSH", "e:short", "only");
        assertThatThrownBy(() -> browser.edit(redis, "e:short", new Edit.ListRemove(5)))
                .hasMessageContaining("index out of range")
                .hasMessageContaining("reload");
        assertThat(browser.read(redis, "e:short").orElseThrow().value()).isEqualTo(new Value.Items(List.of("only")));
        assertThatThrownBy(() -> browser.edit(redis, "e:short", new Edit.HashSet("f", "v")))
                .hasMessageContaining("WRONGTYPE");
    }

    @Test
    void commandRepliesLookLikeRedisCli() {
        assertThat(cmd("GET", "user:1:name")).isEqualTo("\"Ada\"");
        assertThat(cmd("GET", "missing")).isEqualTo("(nil)");
        assertThat(cmd("LLEN", "jobs")).isEqualTo("(integer) 3");
        assertThat(cmd("LRANGE", "jobs", "0", "-1")).isEqualTo("1) \"a\"\n2) \"b\"\n3) \"c\"");
        assertThat(cmd("NOPE")).startsWith("(error) ERR unknown command");
    }

    @Test
    void aWrongPasswordIsExplained() {
        var wrong = new DatabaseTarget("cache", DatabaseTarget.Kind.REDIS, "127.0.0.1", redis.port(), "", "nope", "0");
        assertThatThrownBy(() -> browser.read(wrong, "x"))
                .hasMessageContaining("WRONGPASS")
                .hasMessageContaining("--requirepass");
        var down = new DatabaseTarget("cache", DatabaseTarget.Kind.REDIS, "127.0.0.1", 1, "", "", "0");
        assertThatThrownBy(() -> browser.read(down, "x")).hasMessageContaining("Is it running?");
    }

    @Test
    void binaryBytesAreEscapedLikeRedisCli() {
        assertThat(Resp.text(new byte[] {'o', 'k', (byte) 0xff, 0})).isEqualTo("ok\\xff\\x00");
        assertThat(Resp.text("héllo".getBytes(StandardCharsets.UTF_8))).isEqualTo("héllo");
    }

    @Test
    void memoryShowsTheBiggestKeysAndPrefixes() {
        cmd("SET", "blob:big", "x".repeat(50_000));
        var report = browser.memory(redis, "*", 10_000);

        assertThat(report.usedBytes()).isPositive();
        assertThat(report.totalKeys()).isEqualTo(report.scanned());
        assertThat(report.biggest().getFirst()).satisfies(k -> {
            assertThat(k.key()).isEqualTo("blob:big");
            assertThat(k.length()).isEqualTo(50_000);
            assertThat(k.bytes()).isGreaterThan(50_000);
        });
        assertThat(report.prefixes()).anySatisfy(p -> {
            assertThat(p.prefix()).isEqualTo("user:*");
            assertThat(p.keys()).isGreaterThanOrEqualTo(3);
        });
        cmd("DEL", "blob:big");
    }

    @Test
    void deleteByPatternShowsWhatItWillDeleteFirst() {
        for (int i = 0; i < 25; i++) {
            cmd("SET", "tmp:" + i, "v");
        }
        var preview = browser.matching(redis, "tmp:*", 5);
        assertThat(preview.count()).isEqualTo(25);
        assertThat(preview.sample()).hasSize(5).allMatch(k -> k.startsWith("tmp:"));

        assertThat(browser.deleteMatching(redis, "tmp:*")).isEqualTo(25);
        assertThat(browser.matching(redis, "tmp:*", 5).count()).isZero();
        assertThat(cmd("GET", "user:1:name")).isEqualTo("\"Ada\""); // nothing else touched
    }

    @Test
    void slowLogListsSlowCommands() {
        cmd("CONFIG", "SET", "slowlog-log-slower-than", "0"); // log everything, for the test
        try {
            browser.resetSlowLog(redis);
            cmd("GET", "user:1:name");
            var log = browser.slowLog(redis);
            assertThat(log.thresholdMicros()).isZero();
            assertThat(log.entries()).anySatisfy(e -> assertThat(e.words()).containsExactly("GET", "user:1:name"));
        } finally {
            cmd("CONFIG", "SET", "slowlog-log-slower-than", "10000");
        }
    }

    @Test
    void pubSubAndMonitorStreamEvents() throws Exception {
        var messages = new LinkedBlockingQueue<WatchEvent>();
        var ended = new LinkedBlockingQueue<String>();
        var sub = browser.watch(redis, new WatchKind.Subscribe(List.of("orders.*")), messages::add, ended::add);
        try {
            long receivers = 0;
            for (int i = 0; i < 50 && receivers == 0; i++) {
                receivers = browser.publish(redis, "orders.created", "{\"id\": 7}");
                Thread.sleep(50);
            }
            assertThat(receivers).isEqualTo(1);
            var m = messages.poll(5, TimeUnit.SECONDS);
            assertThat(m).isNotNull();
            assertThat(m.source()).isEqualTo("orders.created");
            assertThat(m.text()).isEqualTo("{\"id\": 7}");
        } finally {
            sub.close();
        }
        assertThat(ended.poll(5, TimeUnit.SECONDS)).isEqualTo("stopped");

        var commands = new LinkedBlockingQueue<WatchEvent>();
        var monitor = browser.watch(redis, new WatchKind.Monitor(), commands::add, x -> {});
        try {
            Thread.sleep(200);
            cmd("GET", "user:1:name");
            WatchEvent e;
            do {
                e = commands.poll(5, TimeUnit.SECONDS);
            } while (e != null && !e.text().contains("user:1:name"));
            assertThat(e).isNotNull();
            assertThat(e.text()).isEqualTo("\"GET\" \"user:1:name\"");
            assertThat(e.source()).contains(":"); // the client's address and port
        } finally {
            monitor.close();
        }
    }

    private static String cmd(String... words) {
        return new RedisBrowser().command(redis, List.of(words));
    }

    /** The command's output, or empty if it failed. */
    private static Optional<String> run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 ? Optional.of(out) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
