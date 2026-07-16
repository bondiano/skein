// Classloader glue between the Clojure RT and Knot (DESIGN.md §4, §6).
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
    compileOnly(libs.fabric.loader)
}
