pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Auto-provisions the JDK 25 toolchain (required by MC 26.x).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://repo.clojars.org/") { name = "Clojars" }
    }
}

rootProject.name = "skein"

include(
    "adapter",
    "runtime",
    "repl",
    "gradle-plugin",
    "core-lib",
    "example-mod",
)

// template/ — standalone repo template for modders (M5), intentionally not part of this build.
