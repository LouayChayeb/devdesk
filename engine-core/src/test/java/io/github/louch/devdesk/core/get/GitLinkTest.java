package io.github.louch.devdesk.core.get;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitLinkTest {

    private static GitLink ok(String text) {
        return ((GitLink.Parsed.Ok) GitLink.parse(text)).link();
    }

    private static String refused(String text) {
        return ((GitLink.Parsed.Refused) GitLink.parse(text)).why();
    }

    @Test
    void theLinksPeoplePasteBecomeACloneUrlAndAFolderName() {
        assertThat(ok("https://github.com/umami-software/umami"))
                .isEqualTo(new GitLink("https://github.com/umami-software/umami.git", "umami", Optional.empty()));
        assertThat(ok("  https://github.com/docker/awesome-compose.git  "))
                .isEqualTo(new GitLink(
                        "https://github.com/docker/awesome-compose.git", "awesome-compose", Optional.empty()));
        assertThat(ok("https://gitlab.com/group/sub/app"))
                .isEqualTo(new GitLink("https://gitlab.com/group/sub/app.git", "app", Optional.empty()));
    }

    @Test
    void aBranchComesFromTheTreePageOrAfterAHash() {
        assertThat(ok("https://github.com/owner/shop/tree/dev").ref()).contains("dev");
        assertThat(ok("https://gitlab.com/group/shop/-/tree/v2.1").ref()).contains("v2.1");
        assertThat(ok("https://github.com/owner/shop#release/1.0"))
                .isEqualTo(new GitLink("https://github.com/owner/shop.git", "shop", Optional.of("release/1.0")));
        // A file's page on GitHub still means the project.
        assertThat(ok("https://github.com/owner/shop/blob/main/README.md").cloneUrl())
                .isEqualTo("https://github.com/owner/shop.git");
    }

    @Test
    void aPasswordInTheLinkIsNeverPassedOn() {
        assertThat(ok("https://me:ghp_secret@github.com/owner/shop").cloneUrl())
                .isEqualTo("https://github.com/owner/shop.git");
    }

    @Test
    void aRepositoryOnThisMachineCanBeCloned() {
        assertThat(ok("file:///srv/git/shop.git"))
                .isEqualTo(new GitLink("file:///srv/git/shop.git", "shop", Optional.empty()));
    }

    @Test
    void whatIsntASafeHttpsProjectLinkIsRefusedWithWhatToDo() {
        assertThat(refused("git@github.com:owner/shop.git")).contains("isn't a link");
        assertThat(refused("http://github.com/owner/shop")).contains("only clones https links");
        assertThat(refused("ssh://git@github.com/owner/shop")).contains("only clones https links");
        assertThat(refused("https://github.com/owner")).contains("doesn't point at a project");
        assertThat(refused("https://github.com/owner/shop#--upload-pack=x")).contains("isn't a branch or tag name");
    }
}
