pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        mavenCentral()
    }
    // The Skein plugin is an included build: example-mod applies
    // `dev.skein.fabric-clojure` the same way a published mod would.
    includeBuild("gradle-plugin")
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
    "core-lib",
    "skein-scripts",
    "example-mod",
)

// gradle-plugin/ — included build (see pluginManagement above), built/tested via the root lifecycle tasks.
// template/ — standalone repo template for modders (M5), intentionally not part of this build.
