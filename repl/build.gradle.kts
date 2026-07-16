// nREPL lifecycle + middleware (DESIGN.md §7): dev out of the box,
// production strictly opt-in, game-thread dispatch middleware.
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
    implementation(libs.nrepl)
}

// Clojure sources ship as sources in the jar; the shared RT loads them
// (module AOT strategy is decided in M1 together with the gradle plugin).
sourceSets.main {
    resources.srcDir("src/main/clojure")
}
