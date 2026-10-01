// cli: the picocli client. Until M3 it runs the engine in-process, so it wires core
// and the Linux adapter itself. From M3 on it only talks to the daemon over HTTP.
plugins {
    id("devdesk.java-conventions")
    application // adds ./gradlew :cli:run and packages a start script
}

dependencies {
    implementation(project(":engine-core"))
    implementation(project(":engine-linux"))
}

application {
    mainClass = "io.github.louch.devdesk.cli.Main"
    applicationName = "devdesk" // name of the generated start script
}
