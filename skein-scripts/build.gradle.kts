// Skein Scripts — a standalone mod that loads plain `.clj` files from
// `config/skein/scripts/` at runtime (no AOT), the opposite trade-off from
// regular mods: convenience and live editing over startup cost. It depends on
// the adapter (shared Clojure runtime + REPL) and core-lib (interop/events).
//
// MC 26.1+ is unobfuscated — non-remapping Loom, plain `implementation`, no
// mappings. Like example-mod, the Skein Gradle plugin wires src/main/clojure,
// AOT before `jar`, and the lints.
//
// The one deliberate difference from every other mod: this mod bundles
// tools.deps at RUNTIME (JiJ), so scripts can pull dependencies from a
// `deps.edn`. Ordinary mods must be self-contained and never ship a dependency
// resolver; a runtime scripting host is the documented exception.
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
// settings-level ones — Clojars (nREPL via :repl) and Maven Central (tools.deps
// transitives) have to be reachable from here.
repositories {
    mavenCentral()
    maven("https://repo.clojars.org/") { name = "Clojars" }
}

// CI builds the matrix of supported MC versions by overriding the catalog
// default, in lockstep with the MC-specific fabric-api release.
val minecraftVersion = providers.gradleProperty("skein.minecraft.version")
    .getOrElse(libs.versions.minecraft.get())
val fabricApiVersion = providers.gradleProperty("skein.fabricapi.version")
    .getOrElse(libs.versions.fabric.api.get())

// tools.deps and its full transitive closure, bundled into the mod jar so
// deps.edn resolution works on a production server (dev already has it on the
// classpath via the plugin's localRuntime). Resolved as a plain configuration;
// each artifact is JiJ-nested below.
val scriptRuntimeLibs: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = true
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    implementation(libs.fabric.loader)
    implementation(project(":adapter"))
    implementation(project(":core-lib"))
    // fabric-api: the /skein reload command and the load-on-server-started
    // lifecycle hook go through it.
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    scriptRuntimeLibs(libs.tools.deps)

    testImplementation(libs.clojure)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // The loader/config unit tests are clojure.test run from source in the
    // test JVM; tools.deps backs the deps.edn resolution test.
    testRuntimeOnly(libs.tools.deps)
}

// The Clojure test namespaces load from source in the test JVM.
sourceSets.test {
    resources.srcDir("src/test/clojure")
}

// JiJ every tools.deps artifact except the ones the runtime already provides.
// Bundling a library the platform ships is not just redundant — an older copy
// can shadow the platform's and break it. The Fabric loader ships ASM and the
// mixin toolchain, and the game ships slf4j; a stale ASM here fails mixin
// application with a VerifyError at boot. clojure/spec/core.specs are the
// adapter's (the Skein plugin also bans bundling them). Everything is filtered
// after evaluation, when the configuration is fully resolved.
val providedCoordinates = setOf(
    "org.clojure:clojure",
    "org.clojure:spec.alpha",
    "org.clojure:core.specs.alpha",
    // The game provides the slf4j API — but only the API. jcl-over-slf4j
    // (also org.slf4j) is NOT provided and stays bundled: maven-resolver's
    // http transport needs org.apache.commons.logging.LogFactory from it.
    "org.slf4j:slf4j-api",
)
// Whole groups the loader/game own — never bundle any version. Shipping an
// older copy shadows the platform's and breaks it (an older ASM fails mixin
// application; an older commons-lang3 has a MutableObject that does not yet
// implement Supplier, which the game relies on).
val providedGroups = setOf(
    "org.ow2.asm", // Fabric loader's ASM + the mixin toolchain build on it.
    "org.apache.commons", // Minecraft ships commons-lang3.
    "commons-codec", // Pulled in transitively by Minecraft's stack.
)
afterEvaluate {
    scriptRuntimeLibs.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
        val id = artifact.moduleVersion.id
        val coordinate = "${id.group}:${id.name}"
        if (coordinate !in providedCoordinates && id.group !in providedGroups) {
            dependencies.add("include", "$coordinate:${id.version}")
        }
    }
}

loom {
    mods {
        create("skein_scripts") {
            sourceSet(sourceSets.getByName("main"))
        }
    }
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

// ---------------------------------------------------------------------------
// Production smoke test: the built jars on the real Fabric server launcher.
// The `test` task covers the loader/config in a plain JVM; this exercises the
// whole mod on a real dedicated server (fabric-server-launch, JiJ extraction,
// no Loom classpath) through the opt-in production REPL:
//   - a script in config/skein/scripts/ loads at the server-started phase;
//   - a config/skein/deps.edn pulls a library that is NOT in the JiJ'd
//     tools.deps closure (data.csv), proving real resolution in production;
//   - editing the script file + (skein-scripts.core/reload!) hot-reloads it.
// Needs network (downloads the launcher, the MC server and the deps.edn lib);
// runs via the dedicated `prodReplSmokeTest` task, never as part of `check`.
// ---------------------------------------------------------------------------

val prodSmoke: SourceSet by sourceSets.creating

dependencies {
    // A plain JVM (no Fabric): the whole test — server orchestration and the
    // nREPL client checks — is clojure.test source in prodSmoke resources,
    // run by clojure.main.
    "prodSmokeImplementation"(libs.clojure)
    "prodSmokeImplementation"(libs.nrepl)
}

// The mods the loader consumes: the adapter (Clojure runtime + REPL) and the
// fat fabric-api; the mod under test is this project's own jar (added below).
val prodSmokeMods: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    prodSmokeMods(project(":adapter"))
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
    description = "Boots the built jars on the real Fabric server launcher and smoke-tests script loading, deps.edn and reload."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath = prodSmoke.runtimeClasspath
    mainClass = "clojure.main"
    // clojure.test does the asserting; -main exits non-zero on failure.
    args("-m", "skein-scripts-smoke.smoke-test")

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
