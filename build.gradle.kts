// Root project aggregates the modules; each module configures itself.
plugins {
    base
    // Declared here (apply false) so Loom and the Skein plugin live in ONE
    // classloader scope shared by all subprojects — Loom breaks when two
    // projects load it into different scopes.
    alias(libs.plugins.loom) apply false
    id("io.github.bondiano.fabric-clojure") apply false
    alias(libs.plugins.spotless) apply false
}

// Java hygiene for all modules: Spotless formats (palantir-java-format),
// Checkstyle lints semantics (config/checkstyle/checkstyle.xml). The
// gradle-plugin included build applies the same pair in its own script.
// (Versions are captured here: the `libs` accessor is root-scoped and not
// visible inside the subprojects block.)
val palantirJavaFormatVersion = libs.versions.palantir.java.format.get()
val checkstyleToolVersion = libs.versions.checkstyle.get()

subprojects {
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "checkstyle")

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("src/*/java/**/*.java")
            palantirJavaFormat(palantirJavaFormatVersion)
            removeUnusedImports()
            formatAnnotations()
        }
    }

    configure<CheckstyleExtension> {
        toolVersion = checkstyleToolVersion
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
        maxWarnings = 0
    }
}

// gradle-plugin is an included build (composite): fold it into the root
// lifecycle so `./gradlew build` covers it too.
tasks.build {
    dependsOn(gradle.includedBuild("gradle-plugin").task(":build"))
}

tasks.check {
    dependsOn(gradle.includedBuild("gradle-plugin").task(":check"))
}
