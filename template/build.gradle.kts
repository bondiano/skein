// A Fabric mod written in Clojure. Loom plus the Skein plugin is the whole
// build: the Skein plugin wires the `src/main/clojure` source set, AOT-compiles
// it before `jar`, runs the reflection/ns-naming lints, generates any mixin
// classes declared with `defmixin`, pins the Clojure version, and starts a dev
// nREPL in every run config. MC 26.x is unobfuscated — non-remapping Loom,
// plain `implementation`, no mappings, no remapJar.
plugins {
    `java-library`
    alias(libs.plugins.loom)
    alias(libs.plugins.skein)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Loom declares project-level repositories, which makes Gradle ignore the
// settings-level ones — re-declare Clojars (Skein artifacts, nREPL) here.
repositories {
    maven("https://repo.clojars.org/") { name = "Clojars" }
}

dependencies {
    minecraft(libs.minecraft)
    implementation(libs.fabric.loader)
    implementation(libs.fabric.api)

    // The Skein adapter (the "clojure" language adapter) and the FP layer.
    // The plugin adds the matching Clojure version itself.
    implementation(libs.skein.adapter)
    implementation(libs.skein.core.lib)
}

// Let Loom know about this mod so dev runs load it.
loom {
    mods {
        create("mymod") {
            sourceSet(sourceSets.getByName("main"))
        }
    }
}

// Substitute ${version} in fabric.mod.json with the project version.
tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

// Optional Skein settings — a mod builds with no `skein { }` block at all.
skein {
    // Dev nREPL port (default 7888). Uncomment to change it.
    // nreplPort = 7888
    //
    // Turn the reflection perf-lint into a build error ("warn" by default,
    // "off" to silence). On MC 26.x reflection is correct in production —
    // this is purely about hot-path performance.
    // reflectionWarnings = "error"
}
