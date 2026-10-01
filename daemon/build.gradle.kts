// daemon: the composition root. It is the only module allowed to know every adapter,
// because it constructs them and hands them to core (manual constructor injection).
plugins {
    id("devdesk.java-conventions")
    application // adds ./gradlew :daemon:run and packages a start script
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-linux"))
    implementation(project(":engine-docker"))
}

application {
    mainClass = "io.github.louch.devdesk.daemon.Main"
    applicationName = "devdeskd" // name of the generated start script
}
