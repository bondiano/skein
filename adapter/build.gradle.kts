// The published library mod: ClojureLanguageAdapter + bootstrap.
// Applies the non-remapping Loom for MC 26.x — it provides the `minecraft`
// dependency scope and Jar-in-Jar nesting via `include` (no remapJar: `jar`
// nests the included jars directly).
plugins {
    `java-library`
    alias(libs.plugins.loom)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
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

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    api(project(":runtime"))
    api(project(":repl"))
    implementation(libs.clojure)
    implementation(libs.fabric.loader)
    compileOnly(libs.slf4j)

    // Jar-in-Jar: the adapter owns the Clojure version for the whole
    // JVM. `include` is non-transitive — clojure's deps are listed
    // explicitly. Non-mod jars get a generated fabric.mod.json wrapper, so
    // Fabric Loader deduplicates them by semver across adapter versions.
    include(project(":runtime"))
    include(project(":repl"))
    include(libs.clojure)
    include(libs.clojure.spec)
    include(libs.clojure.core.specs)
    include(libs.nrepl)
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

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
