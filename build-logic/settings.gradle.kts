// build-logic is a separate, small Gradle build that only produces plugins.
// The main build pulls it in with includeBuild("build-logic") in its settings.
rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal() // the kotlin-dsl plugin's own dependencies come from here
    }
    // Reuse the main build's catalog so versions still live in exactly one file.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
