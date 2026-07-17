// The plugin lives in the monorepo as an included build (composite): the root
// settings.gradle.kts pulls it in via pluginManagement.includeBuild, so
// example-mod applies `dev.skein.fabric-clojure` exactly like a published mod
// would — the "builds out of the box" exit criterion of M1.
pluginManagement {
    repositories {
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
        // Loom as a compileOnly library (run-config wiring).
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
    versionCatalogs {
        // Shared with the root build — the single source of truth for versions.
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "gradle-plugin"
