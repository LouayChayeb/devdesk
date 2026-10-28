// Java 25's AOT cache for a launcher: installDist ends with one training run that records what starting needs (the
// classes it loads and links), and the start script hands that file to Java, which then skips that work. Measured:
// about 0.5 s saved on every CLI command, 1 s on the app's engine. A missing or outdated cache (a new JDK, rebuilt
// jars) only means Java starts the usual way: -Xlog:aot*=off keeps its notice off stdout, where the engine's first
// line is read by the app.
// A module opts in with plugins { id("devdesk.aot") } and sets what the training run does:
//   extra["aotTraining"] = listOf("why", "-f", "...")   (arguments for the launcher; stdin is empty)
import java.io.ByteArrayInputStream
import java.io.OutputStream

plugins {
    application
}

val cacheName = provider { application.applicationName + ".aot" }

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val name = cacheName.get()
        val script = unixScript
        val lines = script.readLines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("DEFAULT_JVM_OPTS='") }
        check(at >= 0) { "the start script template changed: no DEFAULT_JVM_OPTS line in $script" }
        lines.addAll(
            at + 1,
            listOf(
                "",
                "# Java's AOT cache, made by a training run at install: faster starts. Without it Java starts as usual.",
                "if [ -f \"\$APP_HOME/lib/$name\" ]; then",
                "    DEFAULT_JVM_OPTS=\"\$DEFAULT_JVM_OPTS \\\"-XX:AOTCache=\$APP_HOME/lib/$name\\\" \\\"-Xlog:aot*=off\\\"\"",
                "fi",
            ),
        )
        script.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

val aotCache = tasks.register<Exec>("aotCache") {
    description = "Records Java's AOT cache for the installed launcher with one training run."
    val install = tasks.named<Sync>("installDist").map { it.destinationDir }
    val state = layout.buildDirectory.dir("tmp/aot-state") // the training run's own state, never the user's
    doFirst {
        val dir = install.get()
        val cache = dir.resolve("lib/" + cacheName.get())
        cache.delete() // the script would hand the old one to Java, which can't both use and write a cache
        val training = (project.extra.properties["aotTraining"] as? List<*>)?.map { it.toString() } ?: listOf("--help")
        commandLine(listOf(dir.resolve("bin/" + application.applicationName).path) + training)
        environment("JAVA_OPTS", "-XX:AOTCacheOutput=" + cache.path)
        environment("XDG_STATE_HOME", state.get().asFile.path)
        standardInput = ByteArrayInputStream(ByteArray(0))
    }
    isIgnoreExitValue = true // a training run only has to start and stop; the cache is written on exit either way
    standardOutput = OutputStream.nullOutputStream()
    errorOutput = OutputStream.nullOutputStream()
}

tasks.named("installDist") {
    finalizedBy(aotCache)
}
