// engine-core: the domain model. It must stay free of OS, HTTP and Docker code,
// so it has NO project dependencies. Adding one here breaks the architecture.
plugins {
    id("devdesk.java-conventions")
    `java-library`
}
