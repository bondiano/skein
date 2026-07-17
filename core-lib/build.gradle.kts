// core-lib — a separate artifact mods depend on:
// registry DSL, event wrappers registering vars (hot reload), interop helpers.
plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(libs.clojure)
    // M4: compileOnly fabric-api / game classes via Loom for event wrappers.
}

sourceSets.main {
    resources.srcDir("src/main/clojure")
}
