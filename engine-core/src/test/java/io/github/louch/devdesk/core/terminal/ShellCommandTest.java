package io.github.louch.devdesk.core.terminal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.project.ProjectSpec;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ShellCommandTest {

    private final ProjectSpec project = parse("""
            version: 1
            name: shop
            services:
              api:
                command: npm run dev
              db:
                container:
                  image: postgres:16
            """);

    private static ProjectSpec parse(String yaml) {
        try {
            return ConfigParser.parse(yaml, Path.of("/work/shop"), Map.of(), file -> Optional.empty());
        } catch (ConfigException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aContainerGetsAShellInsideIt() {
        assertThat(ShellCommand.forService("shop", project.services().get("db"), "/bin/zsh", "podman"))
                .containsExactly("podman", "exec", "-it", "devdesk-shop-db", "sh", "-c", ShellCommand.BASH_OR_SH);
    }

    @Test
    void aCommandServiceGetsTheUsersOwnShell() {
        assertThat(ShellCommand.forService("shop", project.services().get("api"), "/bin/zsh", "docker"))
                .containsExactly("/bin/zsh");
        assertThat(ShellCommand.forService("shop", project.services().get("api"), "", "docker"))
                .containsExactly("/bin/bash");
    }
}
