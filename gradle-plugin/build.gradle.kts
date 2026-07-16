// Gradle plugin on top of Fabric Loom (DESIGN.md §11): clojure source set,
// AOT before jar, reflection perf-lint, ns lint, nREPL wiring,
// JiJ guard against bundling own clojure.jar.
plugins {
    `java-gradle-plugin`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // M1: compileOnly fabric-loom to integrate with its tasks.
}

gradlePlugin {
    plugins {
        create("skein") {
            // Provisional id until the Clojars group / final coordinates are settled (Pre-M0).
            id = "dev.skein.fabric-clojure"
            implementationClass = "skein.gradle.SkeinGradlePlugin"
        }
    }
}
