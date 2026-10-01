// `kotlin-dsl` turns every *.gradle.kts file under src/main/kotlin into a plugin
// whose id is the file name (a "precompiled script plugin").
plugins {
    `kotlin-dsl`
}

dependencies {
    // Lets the convention plugin apply id("com.diffplug.spotless") (the formatter).
    implementation(libs.spotless.gradle.plugin)
}
