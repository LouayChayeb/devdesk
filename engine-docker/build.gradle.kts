// engine-docker: implements core's ports (interfaces) for Docker/Podman.
plugins {
    id("devdesk.java-conventions")
    `java-library`
}

dependencies {
    // implementation, not api: modules that use this adapter don't automatically
    // see engine-core through it. They must declare core themselves (explicit wiring).
    implementation(project(":engine-core"))
    // docker-java talks to the Docker Engine API; httpclient5 is the transport that speaks HTTP over a Unix socket.
    implementation(libs.docker.java.core)
    implementation(libs.docker.java.httpclient5)
}
