// Mutation testing with PIT: `./gradlew :<module>:pitest`. PIT changes the compiled code in small ways ("mutants":
// a flipped condition, a removed call, a different return value) and runs the tests against each one. A mutant that
// no test notices shows code whose behaviour the tests don't really check. Report: build/reports/pitest/index.html.
//
// PIT's own command-line runner, started as a plain JavaExec task (the PIT Gradle plugin doesn't support this Gradle
// version). Slow on purpose (thousands of test runs), so it isn't part of `build`: CI runs it in its own job.
//
// A module can exclude tests with `extra["pitestExcludedTests"] = "pkg.SlowTest,pkg.Other*"`: tests that only drive
// child JVMs (the real engine, the real CLI) can't kill a mutant in this JVM anyway, and take minutes each.
plugins {
    java
}

val libs = the<VersionCatalogsExtension>().named("libs")

val pitest: Configuration by configurations.creating

dependencies {
    pitest(libs.findLibrary("pitest-command-line").get())
    pitest(libs.findLibrary("pitest-junit5-plugin").get())
}

val sourceSets = the<SourceSetContainer>()

tasks.register<JavaExec>("pitest") {
    group = "verification"
    description = "Mutation testing with PIT (slow). Report: build/reports/pitest/index.html"
    dependsOn("testClasses")
    mainClass.set("org.pitest.mutationtest.commandline.MutationCoverageReport")
    // The tests' whole classpath plus PIT: PIT then uses this JVM's classpath for its test runs.
    classpath = sourceSets["test"].runtimeClasspath + pitest
    val reportDir = layout.buildDirectory.dir("reports/pitest")
    outputs.dir(reportDir)
    doFirst {
        val excluded = (project.findProperty("pitestExcludedTests") ?: extra.properties["pitestExcludedTests"] ?: "")
            .toString()
        args = listOfNotNull(
            "--reportDir", reportDir.get().asFile.absolutePath,
            "--targetClasses", "io.github.louch.devdesk.*",
            "--targetTests", "io.github.louch.devdesk.*",
            // Test helpers live in the same packages (fakes, scripted runtimes): only product code is mutated.
            "--excludedClasses", "*Test,*Test\$*,*.Fake*,*.Scripted*,*.TestEngine*,*.TestContainer*,*.TestPostgres*,*.TestMysql*",
            "--sourceDirs", sourceSets["main"].java.srcDirs.joinToString(",") { it.absolutePath },
            "--threads", Runtime.getRuntime().availableProcessors().coerceAtMost(8).toString(),
            // STRONGER = the defaults plus removed conditionals: harder mutants, fewer gaps hidden.
            "--mutators", "STRONGER",
            "--outputFormats", "XML,HTML",
            "--timestampedReports", "false",
            "--timeoutConst", "10000",
            "--jvmArgs", "--enable-native-access=ALL-UNNAMED",
        ) + (if (excluded.isBlank()) emptyList() else listOf("--excludedTestClasses", excluded))
    }
}
