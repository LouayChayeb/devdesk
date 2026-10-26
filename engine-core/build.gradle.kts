// engine-core: the domain model. It must stay free of OS, HTTP and Docker code,
// so it has NO project dependencies. Adding one here breaks the architecture.
plugins {
    id("devdesk.java-conventions")
    id("devdesk.mutation")
    id("devdesk.fuzz")
    `java-library`
}

dependencies {
    // Reading devdesk.yaml. A plain parsing library: no OS access, so it's allowed in core.
    implementation(libs.jackson.yaml)
}
