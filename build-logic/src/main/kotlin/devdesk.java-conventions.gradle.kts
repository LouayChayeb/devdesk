// Conventions shared by every DevDesk Java module. A module opts in with
// plugins { id("devdesk.java-conventions") } instead of copy-pasting this block.
plugins {
    java
    checkstyle // style checker, built into Gradle
    id("com.diffplug.spotless") // formatter
}

java {
    toolchain {
        // Compile and test with JDK 25 even if Gradle itself runs on another JDK.
        // Gradle finds a local JDK 25 (SDKMAN, setup-java in CI) or fails with a clear error.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Precompiled plugins can't use the generated `libs.` accessors,
// so the catalog is looked up by name instead.
val libs = the<VersionCatalogsExtension>().named("libs")

spotless {
    java {
        // `spotlessCheck` (part of `build`) fails on badly formatted code;
        // `./gradlew spotlessApply` rewrites the files so they pass.
        palantirJavaFormat(libs.findVersion("palantir").get().requiredVersion)
    }
}

checkstyle {
    toolVersion = libs.findVersion("checkstyle").get().requiredVersion
    // One rules file for the whole repo, in <repo>/config/checkstyle/.
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Every javac warning becomes a build failure, so warnings can't pile up unnoticed.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

dependencies {
    // platform(...) imports the BOM: it pins the versions of the JUnit artifacts below.
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testImplementation(libs.findLibrary("assertj-core").get())
    // The launcher starts JUnit inside Gradle's test worker; only needed at runtime.
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed")
        // Full stack traces in the console: a failing test should explain itself.
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
