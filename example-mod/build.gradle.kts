// Demo mod exercising everything: entrypoints, registry DSL, events,
// Java-stub mixin pattern (DESIGN.md §9.1). Doubles as the CI integration test.
plugins {
    `java-library`
    // M0/M1: switch to the non-remapping Loom + the Skein plugin:
    //   alias(libs.plugins.loom)
    // with dependencies (MC 26.x is unobfuscated — no mappings, plain implementation):
    //   minecraft(libs.minecraft)
    //   implementation(libs.fabric.loader)
    //   implementation(libs.fabric.api)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.clojure)
    implementation(project(":core-lib"))
}

sourceSets.main {
    resources.srcDir("src/main/clojure")
}

tasks.processResources {
    inputs.property("version", version)
    filesMatching("fabric.mod.json") {
        expand("version" to version)
    }
}
