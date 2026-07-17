// Demo mod exercising everything: entrypoints, registry DSL, events,
// Java-stub mixin pattern. Doubles as the CI integration test.
// MC 26.x is unobfuscated — non-remapping Loom, plain `implementation`, no mappings.
//
// This is the M1 exit criterion in action: Loom + the Skein plugin is all a
// mod needs — the plugin wires src/main/clojure, AOT before `jar`, the lints
// and a consistent Clojure version.
plugins {
    `java-library`
    alias(libs.plugins.loom)
    id("dev.skein.fabric-clojure")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

// Loom declares project-level repositories, which makes Gradle ignore the
// settings-level ones — Clojars (nREPL via :repl) has to be re-declared here.
repositories {
    maven("https://repo.clojars.org/") { name = "Clojars" }
}

// CI builds the matrix of supported MC versions by overriding the catalog
// default: ./gradlew build -Pskein.minecraft.version=26.1
val minecraftVersion = providers.gradleProperty("skein.minecraft.version")
    .getOrElse(libs.versions.minecraft.get())

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation(libs.fabric.loader)
    implementation(project(":adapter"))
    implementation(project(":core-lib"))

    // Integration test: fabric-loader-junit boots the real loader (Knot,
    // mod discovery, language adapters) inside the JUnit JVM — no game loop.
    testImplementation(libs.fabric.loader.junit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // For the add-lib REPL check: on dev runs the Skein plugin puts
    // tools.deps on the classpath via Loom's localRuntime, but that
    // configuration does not feed the test classpath.
    testRuntimeOnly(libs.tools.deps)
}

tasks.test {
    useJUnitPlatform()
}

loom {
    mods {
        create("skein_example") {
            sourceSet(sourceSets.getByName("main"))
        }
    }
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}

// Non-default port to demonstrate the option (and to coexist with a host
// nREPL that may already own the conventional 7888).
skein {
    nreplPort = 7899
}
