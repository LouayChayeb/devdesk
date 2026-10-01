// engine-linux: implements core's ports (interfaces) for Linux (/proc, cgroups, signals).
plugins {
    id("devdesk.java-conventions")
    `java-library`
}

dependencies {
    // implementation, not api: modules that use this adapter don't automatically
    // see engine-core through it. They must declare core themselves (explicit wiring).
    implementation(project(":engine-core"))
}
