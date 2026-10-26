// engine-linux: implements core's ports (interfaces) for Linux (/proc, cgroups, signals).
plugins {
    id("devdesk.java-conventions")
    id("devdesk.mutation")
    id("devdesk.fuzz")
    `java-library`
}

dependencies {
    // implementation, not api: modules that use this adapter don't automatically
    // see engine-core through it. They must declare core themselves (explicit wiring).
    implementation(project(":engine-core"))
    implementation(libs.jackson.databind) // JSON lines for the history files
}

tasks.test {
    // LibC calls native code through FFM; without this flag Java prints a warning on every run.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
