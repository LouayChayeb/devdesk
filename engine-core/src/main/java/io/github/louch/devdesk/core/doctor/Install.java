package io.github.louch.devdesk.core.doctor;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** How to install a tool or a project's packages, as commands to copy. Shown to the user, never run automatically. */
public final class Install {

    /** What each package manager leaves in node_modules after an install. */
    public static final List<String> MARKERS =
            List.of(".package-lock.json", ".modules.yaml", ".yarn-state.yml", ".yarn-integrity");

    private Install() {}

    /** @param version the version to install ("22"), or "" for any */
    static String hint(String tool, String version) {
        String v = version.isBlank() ? "" : " " + version;
        return switch (tool) {
            // Version managers let several versions live side by side: the usual way on a dev machine.
            case "node" ->
                "install Node" + v + " with nvm: nvm install" + (v.isEmpty() ? " --lts" : v) + " && nvm use"
                        + (v.isEmpty() ? " --lts" : v);
            case "python" ->
                "install Python" + v + " with pyenv: pyenv install" + (v.isEmpty() ? " 3" : v)
                        + ", or sudo apt install python3";
            case "java" ->
                v.isEmpty()
                        ? "sudo apt install default-jdk, or use SDKMAN (sdk install java)"
                        : "sudo apt install openjdk-" + version
                                + "-jdk, or with SDKMAN: sdk list java, then sdk install java " + "<a " + version
                                + " version>";
            case "pnpm" -> "corepack enable pnpm (comes with Node)";
            case "yarn" -> "corepack enable yarn (comes with Node)";
            case "mvn" -> "sudo apt install maven, or use the project's ./mvnw if it has one";
            case "gradle" -> "sudo apt install gradle, or use the project's ./gradlew if it has one";
            case "go" -> "install Go" + v + " from https://go.dev/dl/ (Ubuntu's golang-go package is often older)";
            case "dotnet" ->
                "install the .NET SDK" + v + ": https://learn.microsoft.com/dotnet/core/install/linux (on Ubuntu:"
                        + " sudo apt install dotnet-sdk-"
                        + (version.isBlank() ? "8.0" : version.replaceFirst("^(\\d+\\.\\d+).*", "$1")) + ")";
            default -> "install " + tool;
        };
    }

    /** {@code "packageManager": "yarn@4.18.0"} in package.json: the manager and version the project pins. */
    private static final Pattern PINNED = Pattern.compile("\"packageManager\"\\s*:\\s*\"(yarn|pnpm)@");

    /**
     * The package manager package.json pins (yarn or pnpm), run through corepack, which comes with Node: it uses
     * exactly the pinned version with nothing installed globally and no "corepack enable" (a plain yarn refuses such
     * a project: "Corepack must currently be enabled"). Empty when nothing is pinned, or npm (it comes with Node).
     */
    public static Optional<String> pinnedManager(Function<String, Optional<String>> read) {
        return read.apply("package.json").flatMap(pkg -> {
            Matcher m = PINNED.matcher(pkg);
            return m.find() ? Optional.of("corepack " + m.group(1)) : Optional.empty();
        });
    }

    /** The install command matching the folder's lock file: each manager installs from its own. */
    public static String nodeInstall(Function<String, Optional<String>> read) {
        Optional<String> pinned = pinnedManager(read);
        if (pinned.isPresent()) {
            return pinned.get() + " install";
        }
        if (read.apply("pnpm-lock.yaml").isPresent()) {
            return "pnpm install";
        }
        if (read.apply("yarn.lock").isPresent()) {
            return "yarn install";
        }
        if (read.apply("bun.lock").or(() -> read.apply("bun.lockb")).isPresent()) {
            return "bun install";
        }
        return read.apply("package-lock.json").isPresent() ? "npm ci" : "npm install";
    }
}
