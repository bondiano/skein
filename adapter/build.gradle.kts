// The published library mod: ClojureLanguageAdapter + bootstrap.
// Applies the non-remapping Loom for MC 26.x — it provides the `minecraft`
// dependency scope and Jar-in-Jar nesting via `include` (no remapJar: `jar`
// nests the included jars directly).
plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.loom)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

// Loom declares project-level repositories, which makes Gradle ignore the
// settings-level ones — Clojars (nREPL) has to be re-declared here.
repositories {
    maven("https://repo.clojars.org/") { name = "Clojars" }
}

// CI overrides the MC version to build the support matrix (the adapter is
// MC-version-independent, but Loom needs a game in every configuration).
val minecraftVersion = providers.gradleProperty("skein.minecraft.version")
    .getOrElse(libs.versions.minecraft.get())

// Resolves to the plain jars of our own modules (non-transitive) for the
// flat merge into the adapter jar. Publishing (M5) must not re-declare
// them as external deps of the published artifact.
val bundle: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    api(project(":runtime"))
    api(project(":repl"))
    implementation(libs.clojure)
    implementation(libs.fabric.loader)
    compileOnly(libs.slf4j)

    // Jar-in-Jar for THIRD-PARTY jars only: the adapter owns the Clojure
    // version for the whole JVM, and the generated fabric.mod.json wrappers
    // let Fabric Loader deduplicate them by semver against any other mod
    // that bundles the same libs. `include` is non-transitive — clojure's
    // deps are listed explicitly.
    include(libs.clojure)
    include(libs.clojure.spec)
    include(libs.clojure.core.specs)
    include(libs.nrepl)

    // Own modules ship flat inside the adapter jar (below) — one Skein entry
    // in the mod list, no dev_skein_* wrappers. They version in lockstep
    // with the adapter, so loader-side dedup would buy nothing.
    bundle(project(":runtime"))
    bundle(project(":repl"))
}

tasks.jar {
    dependsOn(bundle)
    from({ bundle.map { zipTree(it) } }) {
        exclude("META-INF/MANIFEST.MF")
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // slf4j is compileOnly above (the game provides it); tests need the API
    // on the classpath — without a provider it no-ops, which is fine.
    testImplementation(libs.slf4j)
}

tasks.test {
    useJUnitPlatform()
}

// The mod version follows <semver>+clojure.<clojure-version>: the base semver
// (gradle.properties) plus the exact Clojure the adapter bundles (from the
// version catalog), as SemVer build metadata. A release then advertises which
// runtime it ships, and it stays a single source of truth — bump the base in
// one place, the Clojure part tracks the catalog. The plain project.version is
// still the Maven/JiJ artifact coordinate.
val clojureVersion = libs.versions.clojure.asProvider().get()
val modVersion = "$version+clojure.$clojureVersion"

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

// The library mod, published so a mod's dev build can pull it as a plain
// `implementation` dependency (end users get it from the mod portals). We
// publish the built jar itself — runtime and repl are merged flat into it and
// Clojure/nREPL are Jar-in-Jar'd, so the POM declares no dependencies: those
// are not external artifacts, and the Skein Gradle plugin adds Clojure to a
// consumer's classpath at the exact version this jar bundles.
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "adapter"
            artifact(tasks.jar)
            artifact(tasks.named("sourcesJar"))
            pom {
                name = "Skein Adapter"
                description = "Fabric language adapter for Clojure: boots one shared Clojure " +
                    "runtime per JVM and resolves Clojure entrypoints, with dev and production nREPL."
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
