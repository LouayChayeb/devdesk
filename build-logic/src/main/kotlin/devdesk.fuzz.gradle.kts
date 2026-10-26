// Coverage-guided fuzzing with Jazzer. A fuzz test is a JUnit method marked @FuzzTest that takes random input and
// states what must always hold ("never throws anything but ConfigException", "what was written reads back the same").
//
// Two ways it runs:
// - in `./gradlew test` (regression mode): once per input saved in src/test/resources/<package>/<Class>Inputs/<method>/,
//   so every crash ever found stays a normal, fast test;
// - with JAZZER_FUZZ=1 (fuzzing mode): Jazzer watches which branches each input reaches and keeps mutating the inputs
//   that reach new ones, until maxDuration or a crash. `scripts/fuzz.sh` runs every target that way.
plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libs.findLibrary("jazzer-junit").get())
}

tasks.withType<Test>().configureEach {
    // Jazzer adds its coverage instrumentation as a Java agent at runtime, which JDK 21+ only allows when asked.
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // Fuzzing mode reads JAZZER_FUZZ; a changed value must rerun the tests, not reuse the last (regression) result.
    inputs.property("jazzerFuzz", providers.environmentVariable("JAZZER_FUZZ").orElse(""))
    inputs.property("jazzerMaxDuration", providers.environmentVariable("JAZZER_MAX_DURATION").orElse(""))
}
