pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        // The Skein Gradle plugin (dev.skein.fabric-clojure) is published here.
        maven("https://repo.clojars.org/") { name = "Clojars" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the JDK 25 toolchain MC 26.x requires (a local JDK may
    // be older). Remove if your machine already runs JDK 25.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        // Skein artifacts (adapter, core-lib) and nREPL.
        maven("https://repo.clojars.org/") { name = "Clojars" }
    }
}

rootProject.name = "mymod"
