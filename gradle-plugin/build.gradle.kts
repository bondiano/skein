// Gradle plugin on top of Fabric Loom: clojure source tree,
// AOT before jar, reflection perf-lint, ns lint, JiJ guard against bundling
// adapter-provided jars. Lives as an included build — see settings.gradle.kts.
plugins {
    `java-gradle-plugin`
    checkstyle
    alias(libs.plugins.spotless)
}

// Same Java hygiene as the root build's subprojects (this is an included
// build, so it configures the pair itself).
spotless {
    java {
        target("src/*/java/**/*.java")
        palantirJavaFormat(libs.versions.palantir.java.format.get())
        removeUnusedImports()
        formatAnnotations()
    }
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    configFile = file("../config/checkstyle/checkstyle.xml")
    maxWarnings = 0
}

java {
    toolchain {
        // Unlike the runtime modules (JDK 25, the game's JVM), the plugin runs
        // inside the Gradle daemon — target Gradle's supported baseline.
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// Bake the catalog's Clojure/nREPL versions into the plugin jar: mods
// applying the plugin get the exact versions the adapter bundles.
val versionsDir = layout.buildDirectory.dir("generated/versions")
val generateVersions = tasks.register<WriteProperties>("generateVersions") {
    destinationFile = versionsDir.map { it.file("skein/gradle/versions.properties") }
    // asProvider(): `clojure` is both a version and a prefix (clojure-spec, …).
    property("clojure", libs.versions.clojure.asProvider().get())
    property("nrepl", libs.versions.nrepl.get())
}

sourceSets.main {
    resources.srcDir(files(versionsDir).builtBy(generateVersions))
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
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
