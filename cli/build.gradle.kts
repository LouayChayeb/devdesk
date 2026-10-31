// cli: the picocli client. It runs the engine in-process, so it wires core and the Linux and Docker adapters itself.
plugins {
    id("devdesk.java-conventions")
    application // adds ./gradlew :cli:run and packages a start script
    id("devdesk.aot") // faster starts: see build-logic/src/main/kotlin/devdesk.aot.gradle.kts
}

// The AOT training run: a project check, which loads what most commands use (the parser, the diagnosis rules).
extra["aotTraining"] =
    listOf("why", "-f", rootProject.file("daemon/src/test/resources/projects/three-tier/devdesk.yaml").path)

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-linux"))
    implementation(project(":engine-docker"))
    implementation(libs.picocli)
    // docker-java logs through SLF4J; without a backend it prints a warning on every start. DevDesk's own
    // messages don't use SLF4J yet, so a "no-op" backend that drops the library's internal logging is enough.
    runtimeOnly(libs.slf4j.nop)
}

application {
    mainClass = "io.github.louch.devdesk.cli.Main"
    applicationName = "devdesk" // name of the generated start script
    // engine-linux calls libc's kill() through FFM; without this Java prints a warning on every run.
    // UTF-8 output whatever the locale: in a bare container or an SSH session with no LANG, Java would turn every
    // ✓ and ✗ into "?". Same flags as the .deb's launcher (packaging/linux/package.sh).
    applicationDefaultJvmArgs =
        listOf("--enable-native-access=ALL-UNNAMED", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
