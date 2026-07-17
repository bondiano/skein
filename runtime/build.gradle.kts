// Classloader glue between the Clojure RT and Knot.
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
    // Provided by the game environment (Minecraft ships slf4j).
    compileOnly(libs.slf4j)
}
