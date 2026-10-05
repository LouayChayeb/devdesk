// cli: the picocli client. Until M3 it runs the engine in-process, so it wires core
// and the Linux adapter itself. From M3 on it only talks to the daemon over HTTP.
plugins {
    id("devdesk.java-conventions")
    application // adds ./gradlew :cli:run and packages a start script
}

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
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
