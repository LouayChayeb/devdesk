package io.github.louch.devdesk.core.branch;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.louch.devdesk.core.config.ConfigException;
import io.github.louch.devdesk.core.config.ConfigParser;
import io.github.louch.devdesk.core.project.ProjectSpec;
import io.github.louch.devdesk.core.project.Workload;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BranchStacksTest {

    private static ProjectSpec project() throws ConfigException {
        return ConfigParser.parse("""
                version: 1
                name: shop
                services:
                  db:
                    container:
                      image: postgres:18
                      volumes: ["shop-db:/var/lib/postgresql", "./init:/docker-entrypoint-initdb.d"]
                    per_branch: true
                  cache:
                    container:
                      image: redis:8
                      volumes: ["shop-cache:/data"]
                """, Path.of("/work/shop"), Map.of(), f -> Optional.empty());
    }

    private static Workload.Container container(ProjectSpec p, String service) {
        return (Workload.Container) p.services().get(service).workload();
    }

    @Test
    void readsTheBranchFromHead() {
        assertThat(BranchStacks.branch("ref: refs/heads/feature/login\n")).contains("feature/login");
        assertThat(BranchStacks.branch("3f2a9c1d0e\n")).isEmpty(); // detached
        assertThat(BranchStacks.gitDirOf("gitdir: /repo/.git/worktrees/x\n")).contains("/repo/.git/worktrees/x");
        assertThat(BranchStacks.suffix("Feature/Login_2")).isEqualTo("feature-login-2");
        assertThat(BranchStacks.suffix("main")).isEmpty();
    }

    @Test
    void onlyPerBranchServicesGetTheirOwnNamedVolumes() throws ConfigException {
        ProjectSpec p = BranchStacks.apply(project(), Optional.of("feature/login"));
        assertThat(container(p, "db").volumes())
                .containsExactly(
                        "shop-db--feature-login:/var/lib/postgresql", "/work/shop/init:/docker-entrypoint-initdb.d");
        assertThat(container(p, "cache").volumes()).containsExactly("shop-cache:/data");
        assertThat(p.services().get("db").perBranch()).isTrue();
        assertThat(BranchStacks.perBranch(p)).containsExactly("db");
    }

    @Test
    void theMainBranchKeepsTheDataItAlreadyHas() throws ConfigException {
        ProjectSpec main = BranchStacks.apply(project(), Optional.of("main"));
        assertThat(container(main, "db").volumes()).first().isEqualTo("shop-db:/var/lib/postgresql");
        ProjectSpec detached = BranchStacks.apply(project(), Optional.empty());
        assertThat(container(detached, "db").volumes()).first().isEqualTo("shop-db:/var/lib/postgresql");
    }
}
