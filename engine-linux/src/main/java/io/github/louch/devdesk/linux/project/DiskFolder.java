package io.github.louch.devdesk.linux.project;

import io.github.louch.devdesk.core.setup.FolderScan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** A project folder on disk for the setup scan. Reads stay inside it, and only small files are read. */
public final class DiskFolder implements FolderScan.Folder {

    /** Bigger files aren't build files: a 5 MB "package.json" is someone's data. */
    private static final long MAX_FILE = 1024 * 1024;

    private final Path root;

    public DiskFolder(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return root.getFileName() == null ? "project" : root.getFileName().toString();
    }

    @Override
    public List<String> subfolders() {
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    @Override
    public List<String> files() {
        try (Stream<Path> list = Files.list(root)) {
            return list.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of();
        }
    }

    @Override
    public Optional<String> read(String relativePath) {
        Path file = root.resolve(relativePath).normalize();
        try {
            if (!file.startsWith(root) || !Files.isRegularFile(file) || Files.size(file) > MAX_FILE) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(file));
        } catch (IOException | UncheckedIOException e) {
            return Optional.empty(); // binary or unreadable: not a build file
        }
    }

    /**
     * Whether nothing listens on {@code port} yet. Binding on all addresses fails both for a program on 127.0.0.1 and
     * for Docker's proxy on 0.0.0.0.
     */
    public static boolean portFree(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
