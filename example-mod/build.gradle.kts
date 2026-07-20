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
    id("io.github.bondiano.fabric-clojure")
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

// fabric-api releases are MC-version-specific — the CI matrix overrides it
// in lockstep with skein.minecraft.version.
val fabricApiVersion = providers.gradleProperty("skein.fabricapi.version")
    .getOrElse(libs.versions.fabric.api.get())

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation(libs.fabric.loader)
    implementation(project(":adapter"))
    implementation(project(":core-lib"))
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

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
    // The live-server L2 suite boots a real dedicated server and runs in its
    // own forked JVM (l2ServerTest) — keep it out of the lightweight suite.
    exclude("skein/example/L2ServerTests.class")
}

// Live-server integration for the FP domain layer (L2): boots a real
// dedicated server inside the fabric-loader-junit env (server side, a scratch
// world dir as the working directory) and exercises world/entity effects on
// the real server thread. Separate task = separate JVM, so the full server
// boot never disturbs the `test` suite.
val l2RunDir = layout.buildDirectory.dir("l2-run")
val l2ServerTest by tasks.registering(Test::class) {
    description = "Boots a headless dedicated server and runs the L2 domain integration tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("skein/example/L2ServerTests.class")
    // A dedicated server needs the server-side Fabric environment.
    systemProperty("fabric.side", "server")
    // This suite drives the game directly, not over a REPL — keep the dev
    // nREPL off so it never contends for a port with a host process.
    systemProperty("skein.nrepl.enabled", "false")
    doFirst { l2RunDir.get().asFile.mkdirs() }
    workingDir = l2RunDir.get().asFile
}

// Export the resolved runtime classpath (the deobfuscated Minecraft jar + its
// library set that Loom resolves here) so core-lib's tests can build real game
// types without applying Loom themselves. The Skein project jars are dropped:
// core-lib loads its own Clojure sources from resources, and pulling its built
// jar back in would be circular. Resolved in this project's own context, which
// Gradle 9 requires.
val exportGameClasspath by tasks.registering {
    val runtimeClasspath = configurations.named("runtimeClasspath")
    val outputFile = layout.buildDirectory.file("skein-game-classpath.txt")
    outputs.file(outputFile)
    doLast {
        val skeinJars = Regex("/(adapter|core-lib|runtime|repl|example-mod)/build/")
        outputFile.get().asFile.writeText(
            runtimeClasspath.get()
                .filterNot { skeinJars.containsMatchIn(it.path) }
                .joinToString("\n") { it.absolutePath })
    }
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

// ---------------------------------------------------------------------------
// Production smoke test: the built jars on the real Fabric server launcher.
// The dev environment is covered by `test` (fabric-loader-junit), but the
// production topology differs (fabric-server-launch, JiJ extraction, no Loom
// classpath) — so the opt-in production REPL is exercised against a real
// dedicated server: boot, connect over nREPL, eval interop, verify.
// Needs network (downloads the launcher and the MC server); runs via the
// dedicated `prodReplSmokeTest` task, never as part of `check`.
// ---------------------------------------------------------------------------

val prodSmoke: SourceSet by sourceSets.creating

dependencies {
    // A plain JVM (no Fabric): the whole test — server orchestration and the
    // nREPL client checks — is clojure.test source in prodSmoke resources,
    // run by clojure.main.
    "prodSmokeImplementation"(libs.clojure)
    "prodSmokeImplementation"(libs.nrepl)
}

// The adapter jar as the loader would consume it (flat-merged own modules +
// JiJ third-party jars) — resolved artifact only, no transitive deps.
val prodSmokeMods: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    prodSmokeMods(project(":adapter"))
    // The fat fabric-api jar: the loader extracts its JiJ modules itself.
    prodSmokeMods("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}

val fabricLauncherUrl = "https://meta.fabricmc.net/v2/versions/loader/" +
    "$minecraftVersion/${libs.versions.fabric.loader.get()}/${libs.versions.fabric.installer.get()}/server/jar"

val downloadFabricServerLauncher by tasks.registering {
    description = "Downloads fabric-server-launch.jar for the production REPL smoke test."
    val url = fabricLauncherUrl
    val target = layout.buildDirectory.file("prod-smoke/fabric-server-launch-$minecraftVersion.jar")
    inputs.property("url", url)
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        uri(url).toURL().openStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
    }
}

val prodReplSmokeTest by tasks.registering(JavaExec::class) {
    description = "Boots the built jars on the real Fabric server launcher and smoke-tests the opt-in production REPL."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = prodSmoke.runtimeClasspath
    mainClass = "clojure.main"
    // clojure.test does the asserting; -main exits non-zero on failure.
    args("-m", "skein-prod-smoke.smoke-test")

    val launcher = downloadFabricServerLauncher.map { it.outputs.files.singleFile }
    val modJars = prodSmokeMods + tasks.jar.get().outputs.files
    val runDir = layout.buildDirectory.dir("prod-smoke/run")
    inputs.files(modJars)
    inputs.files(downloadFabricServerLauncher)
    jvmArgumentProviders.add {
        listOf(
            "-Dskein.smoke.launcherJar=${launcher.get().absolutePath}",
            "-Dskein.smoke.modJars=${modJars.asPath}",
            "-Dskein.smoke.runDir=${runDir.get().asFile.absolutePath}",
        )
    }
}
