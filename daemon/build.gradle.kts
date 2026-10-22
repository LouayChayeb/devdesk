// daemon: the composition root. It is the only module allowed to know every adapter,
// because it constructs them and hands them to core (manual constructor injection).
// Since ADR 0005 it isn't a background daemon: the desktop app starts it as its "engine" and stops it on close.
plugins {
    id("devdesk.java-conventions")
    application // adds ./gradlew :daemon:run and packages a start script
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-linux"))
    implementation(project(":engine-docker"))
    implementation(project(":engine-data"))
    implementation(libs.javalin) // small HTTP + WebSocket server
    implementation(libs.jackson.databind) // JSON for the API
    runtimeOnly(libs.slf4j2.nop) // Javalin, Jetty and docker-java log through SLF4J; DevDesk prints its own messages
}

application {
    mainClass = "io.github.louch.devdesk.daemon.Main"
    applicationName = "devdeskd" // name of the generated start script
    // preferIPv4Stack: listen on a plain IPv4 socket (127.0.0.1), not an IPv6 socket carrying IPv4
    // ("::ffff:127.0.0.1"), which some tools (like WSL's localhost forwarding) don't handle.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Djava.net.preferIPv4Stack=true")
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
