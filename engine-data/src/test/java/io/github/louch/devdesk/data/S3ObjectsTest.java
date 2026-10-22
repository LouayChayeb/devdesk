package io.github.louch.devdesk.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.louch.devdesk.core.data.DataException;
import io.github.louch.devdesk.core.data.DatabaseTarget;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Against a real S3 server (RustFS, which speaks S3 like MinIO) with its default keys. */
@Tag("docker")
class S3ObjectsTest {

    private static TestContainer server;
    private static DatabaseTarget target;
    private final S3Objects s3 = new S3Objects(Clock.systemUTC());

    @BeforeAll
    static void start() throws Exception {
        server = TestContainer.start("s3", "rustfs/rustfs:latest", 9000, port -> {
            try {
                new S3Objects(Clock.systemUTC()).buckets(target(port));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        });
        target = target(server.port);
    }

    private static DatabaseTarget target(int port) {
        return new DatabaseTarget(
                "files", DatabaseTarget.Kind.OBJECTS, "127.0.0.1", port, "rustfsadmin", "rustfsadmin", "");
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    void bucketsFoldersFilesPreviewAndDelete() {
        s3.createBucket(target, "invoices");
        assertThat(s3.buckets(target)).extracting(b -> b.name()).contains("invoices");

        s3.write(
                target,
                "invoices",
                "2026/oct/a b é.txt",
                "hello from DevDesk".getBytes(StandardCharsets.UTF_8),
                "text/plain");
        s3.write(target, "invoices", "2026/readme.md", "# readme".getBytes(StandardCharsets.UTF_8), "text/markdown");
        s3.write(target, "invoices", "top.json", "{}".getBytes(StandardCharsets.UTF_8), "application/json");

        var top = s3.list(target, "invoices", "", Optional.empty());
        assertThat(top.folders()).containsExactly("2026/");
        assertThat(top.files()).extracting(f -> f.key()).containsExactly("top.json");

        var year = s3.list(target, "invoices", "2026/", Optional.empty());
        assertThat(year.folders()).containsExactly("2026/oct/");
        assertThat(year.files()).extracting(f -> f.key()).containsExactly("2026/readme.md");

        var start = s3.read(target, "invoices", "2026/oct/a b é.txt", 5);
        assertThat(new String(start.bytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        assertThat(start.size()).isEqualTo(18);
        assertThat(start.truncated()).isTrue();
        assertThat(start.contentType()).startsWith("text/plain");

        s3.delete(target, "invoices", "top.json");
        assertThat(s3.list(target, "invoices", "", Optional.empty()).files()).isEmpty();
    }

    @Test
    void wrongKeysAreExplained() {
        var wrong = new DatabaseTarget(
                "files", DatabaseTarget.Kind.OBJECTS, "127.0.0.1", server.port, "rustfsadmin", "nope", "");
        assertThatThrownBy(() -> s3.buckets(wrong))
                .isInstanceOf(DataException.class)
                .hasMessageContaining("MINIO_ROOT_USER");
    }

    @Test
    void aMissingBucketIsExplained() {
        assertThatThrownBy(() -> s3.list(target, "nothing-here", "", Optional.empty()))
                .hasMessageContaining("bucket doesn't exist");
    }
}
