// Settings script: runs before any build script and decides WHICH projects exist.
// Each include() below becomes a subproject whose directory has the same name.
pluginManagement {
    // Our own convention plugins (devdesk.java-conventions) are built from this folder.
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    // One repository list for every module. A module that declares its own
    // repositories {} fails the build, so dependencies can't come from surprise places.
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "devdesk"

include(
    "engine-core", // pure domain, depends on nothing
    "engine-linux", // Linux adapters for core's ports
    "engine-docker", // Docker/Podman adapter for core's ports
    "engine-data", // Workbench adapters: databases (JDBC), later Redis and HTTP
    "daemon", // wires everything, later serves the HTTP API
    "cli", // picocli client (runs the engine in-process until M3)
)
