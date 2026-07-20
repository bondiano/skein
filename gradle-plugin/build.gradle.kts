// Gradle plugin on top of Fabric Loom: clojure source tree,
// AOT before jar, reflection perf-lint, ns lint, JiJ guard against bundling
// adapter-provided jars. Lives as an included build — see settings.gradle.kts.
plugins {
    `java-gradle-plugin`
    `maven-publish`
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
        // inside the Gradle daemon. Java 21: Loom (compileOnly) requires it,
        // so every consumer's build JVM is at least 21 anyway.
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

// Bake the catalog's Clojure/nREPL versions into the plugin jar: mods
// applying the plugin get the exact versions the adapter bundles.
val versionsDir = layout.buildDirectory.dir("generated/versions")
val generateVersions = tasks.register<WriteProperties>("generateVersions") {
    destinationFile = versionsDir.map { it.file("skein/gradle/versions.properties") }
    // asProvider(): `clojure` is both a version and a prefix (clojure-spec, …).
    property("clojure", libs.versions.clojure.asProvider().get())
    property("nrepl", libs.versions.nrepl.get())
    property("tools-deps", libs.versions.tools.deps.get())
    property("malli", libs.versions.malli.get())
    property("dynaload", libs.versions.dynaload.get())
    property("tools-logging", libs.versions.tools.logging.get())
}

sourceSets.main {
    resources.srcDir(files(versionsDir).builtBy(generateVersions))
}

dependencies {
    // Only to configure Loom run configs when the mod applies Loom; the
    // plugin never loads these classes otherwise.
    compileOnly(libs.loom.lib)
    // groovy.json for the fabric.mod.json mixin-config patch (Gradle's own Groovy).
    implementation(localGroovy())

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

gradlePlugin {
    plugins {
        create("skein") {
            id = "io.github.bondiano.fabric-clojure"
            implementationClass = "skein.gradle.SkeinGradlePlugin"
            displayName = "Skein — Clojure for Fabric"
            description = "Builds a Fabric mod written in Clojure on top of the non-remapping " +
                    "Loom: a src/main/clojure source set, AOT before jar, reflection/ns lints, " +
                    "defmixin codegen, pinned Clojure version, and a dev nREPL in every run."
        }
    }
}

// Published to Clojars/Maven so a mod applies `io.github.bondiano.fabric-clojure` via
// pluginManagement. `maven-publish` + `java-gradle-plugin` create two
// publications: `pluginMaven` (the implementation jar) and the plugin marker
// `io.github.bondiano.fabric-clojure:io.github.bondiano.fabric-clojure.gradle.plugin`. Only the
// implementation jar is renamed off the project name; the marker keeps its
// derived coordinates so plugin resolution works.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "Skein Gradle Plugin"
            description = "Gradle plugin that builds Fabric mods written in Clojure with Skein."
            url = "https://github.com/bondiano/skein"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://opensource.org/licenses/MIT"
                }
            }
            developers {
                developer {
                    id = "bondiano"
                    name = "bondiano"
                }
            }
            scm {
                url = "https://github.com/bondiano/skein"
                connection = "scm:git:https://github.com/bondiano/skein.git"
                developerConnection = "scm:git:ssh://git@github.com/bondiano/skein.git"
            }
        }
    }
    repositories {
        // Clojars deploy: -PclojarsUsername / -PclojarsPassword (a deploy token)
        // or CLOJARS_USERNAME / CLOJARS_PASSWORD. `publishToMavenLocal` needs none.
        maven {
            name = "Clojars"
            url = uri("https://repo.clojars.org")
            credentials {
                username = (providers.gradleProperty("clojarsUsername")
                    .orElse(providers.environmentVariable("CLOJARS_USERNAME"))).orNull
                password = (providers.gradleProperty("clojarsPassword")
                    .orElse(providers.environmentVariable("CLOJARS_PASSWORD"))).orNull
            }
        }
    }
}

// The implementation jar carries the project name by default (gradle-plugin);
// give it the published artifact name. The marker publication is left alone.
afterEvaluate {
    (publishing.publications.getByName("pluginMaven") as MavenPublication).artifactId =
        "skein-gradle-plugin"
}
