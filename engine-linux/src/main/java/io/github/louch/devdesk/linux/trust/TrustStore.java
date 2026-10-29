package io.github.louch.devdesk.linux.trust;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which project folders the user trusts, and for which exact devdesk.yaml content.
 *
 * <p>A devdesk.yaml runs arbitrary commands, so a cloned repo must never start anything until the user has seen
 * what it runs and said yes (explicit trust). Any change to the file changes its fingerprint and asks again.
 * Trust is per file, not per folder: two files in one folder ({@code -f devdesk.broken.yaml}) are separate projects.
 * The list is plain text, one line per file: {@code <sha256>  <absolute path of devdesk.yaml>}.
 */
public final class TrustStore {

    private final Path file;

    public TrustStore(Path file) {
        this.file = file;
    }

    /** {@code $XDG_STATE_HOME/devdesk/trust.txt}, defaulting to {@code ~/.local/state/devdesk/trust.txt}. */
    public static Path defaultFile(Map<String, String> env) {
        String state = env.getOrDefault("XDG_STATE_HOME", "");
        Path base = state.isBlank() ? Path.of(env.getOrDefault("HOME", "/tmp"), ".local", "state") : Path.of(state);
        return base.resolve("devdesk").resolve("trust.txt");
    }

    /** SHA-256 of the exact file content: one changed character gives a different fingerprint. */
    public static String fingerprint(String devdeskYaml) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(devdeskYaml.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }

    public boolean isTrusted(Path projectFile, String fingerprint) {
        return fingerprint.equals(read().get(key(projectFile)));
    }

    /** Remembers the file with this fingerprint, replacing an older fingerprint for the same file. */
    public void trust(Path projectFile, String fingerprint) {
        Map<String, String> all = read();
        all.put(key(projectFile), fingerprint);
        StringBuilder text = new StringBuilder();
        all.forEach((path, hash) -> text.append(hash).append("  ").append(path).append('\n'));
        writeAtomically(text.toString());
    }

    /** Every project file DevDesk was ever allowed to run: the projects it knows. */
    public List<Path> knownFiles() {
        return read().keySet().stream().map(Path::of).toList();
    }

    /** Whether this file is trusted at all, whatever its content. */
    public boolean knows(Path projectFile) {
        return read().containsKey(key(projectFile));
    }

    /** Forgets the file: next time it's opened, DevDesk asks again ("Remove" does this). */
    public void forget(Path projectFile) {
        Map<String, String> all = read();
        if (all.remove(key(projectFile)) != null) {
            StringBuilder text = new StringBuilder();
            all.forEach(
                    (path, hash) -> text.append(hash).append("  ").append(path).append('\n'));
            writeAtomically(text.toString());
        }
    }

    private static String key(Path projectFile) {
        return projectFile.toAbsolutePath().normalize().toString();
    }

    private Map<String, String> read() {
        Map<String, String> all = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return all;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                int gap = line.indexOf("  ");
                if (gap == 64) { // a SHA-256 in hex is exactly 64 characters
                    all.put(line.substring(gap + 2), line.substring(0, gap));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the trust list " + file, e);
        }
        return all;
    }

    /**
     * Write a temporary file, flush it to disk, then rename it over the old one. A rename is atomic on Linux, so a
     * crash leaves either the old list or the new one, never half a file.
     */
    private void writeAtomically(String content) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "trust", ".tmp");
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
                channel.force(true); // fsync: the bytes are on disk before the rename makes them visible
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the trust list " + file, e);
        }
    }
}
