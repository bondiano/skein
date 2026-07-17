package skein.gradle;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.jvm.tasks.ProcessResources;

/**
 * Skein Gradle plugin, applied by mod authors on top of the non-remapping
 * Fabric Loom. MC 26.1+ is unobfuscated: no remapping, no mappings — the
 * plugin's whole job is the Clojure build pipeline:
 *
 * <ul>
 *   <li>{@code src/main/clojure} source tree, AOT-compiled before {@code jar}
 *       (mods are AOT-only and ship classes, never sources);</li>
 *   <li>reflection perf-lint — warning by default, opt-in error;</li>
 *   <li>ns-naming lint — ns root must be the mod id, warning;</li>
 *   <li>build failure if the mod tries to JiJ its own clojure.jar;</li>
 *   <li>a consistent Clojure version added automatically — the one the
 *       adapter bundles, baked in from the shared version catalog.</li>
 * </ul>
 */
public final class SkeinGradlePlugin implements Plugin<Project> {

    private static final String CLOJURE_SOURCE_DIR = "src/main/clojure";
    private static final String CLOJURE_CLASSES_DIR = "classes/clojure/main";
    private static final String COMPILE_CLOJURE_TASK = "compileClojure";

    /** Loom's Jar-in-Jar configuration. */
    private static final String INCLUDE_CONFIGURATION = "include";

    /** Provided by the Skein adapter for the whole JVM — never bundled by mods. */
    private static final Set<String> ADAPTER_PROVIDED =
            Set.of("org.clojure:clojure", "org.clojure:spec.alpha", "org.clojure:core.specs.alpha", "nrepl:nrepl");

    @Override
    public void apply(Project project) {
        SkeinExtension extension = project.getExtensions().create(SkeinExtension.NAME, SkeinExtension.class);
        extension.getReflectionWarnings().convention("warn");
        extension.getNsLint().convention(true);
        extension.getNreplPort().convention(7888);

        project.getPlugins().withType(JavaPlugin.class, javaPlugin -> configureClojureBuild(project, extension));
        banBundlingAdapterProvidedJars(project);
        project.getPluginManager()
                .withPlugin("net.fabricmc.fabric-loom", loomApplied -> configureLoom(project, extension));
    }

    /**
     * Dev-run wiring on top of Loom: the nREPL port property for every run
     * config, and tools.deps on the dev-only classpath so {@code add-lib!}
     * works in the REPL ({@code localRuntime} never reaches the jar or the
     * published metadata).
     */
    private void configureLoom(Project project, SkeinExtension extension) {
        LoomGradleExtensionAPI loom =
                (LoomGradleExtensionAPI) project.getExtensions().getByName("loom");
        // Loom realizes run configs during script evaluation — read the port
        // only after the build script (and its skein { } block) has run.
        project.afterEvaluate(evaluated -> loom.getRuns()
                .configureEach(run -> run.property(
                        "skein.nrepl.port", extension.getNreplPort().get().toString())));
        project.getDependencies().add("localRuntime", "org.clojure:tools.deps:" + SkeinVersions.toolsDeps());
    }

    private void configureClojureBuild(Project project, SkeinExtension extension) {
        SourceSet main = project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        Directory clojureSrc = project.getLayout().getProjectDirectory().dir(CLOJURE_SOURCE_DIR);
        Provider<Directory> clojureClasses =
                project.getLayout().getBuildDirectory().dir(CLOJURE_CLASSES_DIR);

        extension
                .getAotNamespaces()
                .convention(project.provider(() -> ClojureNamespaces.discover(clojureSrc.getAsFile())));
        extension.getModId().convention(project.provider(() -> findModId(main)));

        // The one-line version story: applying the plugin pins the same
        // Clojure the adapter bundles; mods never spell the version out.
        project.getDependencies()
                .add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, "org.clojure:clojure:" + SkeinVersions.clojure());

        // Build-time-only deps of the mixins.edn path (malli validation).
        // Resolved from the mod's repositories, but only ever when a
        // mixins.edn exists — the defmixin path needs none of it.
        Configuration mixinTooling = project.getConfigurations().create("skeinMixinTooling", configuration -> {
            configuration.setCanBeConsumed(false);
            configuration.setDescription("Skein build-time mixins.edn validation (malli); never shipped.");
            configuration.defaultDependencies(dependencies ->
                    dependencies.add(project.getDependencies().create("metosin:malli:" + SkeinVersions.malli())));
        });
        Provider<List<File>> mixinEdnFiles = project.provider(() -> main.getResources().getSrcDirs().stream()
                .map(dir -> new File(dir, "mixins.edn"))
                .filter(File::isFile)
                .toList());

        TaskProvider<ClojureCompileTask> compileClojure = project.getTasks()
                .register(COMPILE_CLOJURE_TASK, ClojureCompileTask.class, task -> {
                    task.setGroup(BasePlugin.BUILD_GROUP);
                    task.setDescription("AOT-compiles the mod's Clojure namespaces (Skein).");
                    task.getClojureSources().from(clojureSrc);
                    task.getNamespaces().set(extension.getAotNamespaces());
                    task.getDestinationDirectory().set(clojureClasses);
                    task.getReflectionWarnings().set(extension.getReflectionWarnings());
                    task.getModId().set(extension.getModId());
                    task.getNsLint().set(extension.getNsLint());
                    task.getMixinEdnFiles().from(mixinEdnFiles);
                    task.getMixinResourceDirs()
                            .from(project.provider(() -> main.getResources().getSrcDirs()));
                    // clojure.lang.Compile requires the sources and the compile
                    // path itself on the classpath, plus the mod's own Java
                    // classes (interop stubs) and the runtime dependencies.
                    task.classpath(
                            clojureSrc,
                            clojureClasses,
                            main.getOutput().getClassesDirs(),
                            project.getConfigurations().getByName(main.getRuntimeClasspathConfigurationName()));
                    task.classpath(project.files((Callable<Object>)
                            () -> task.getMixinEdnFiles().isEmpty() ? project.files() : mixinTooling));
                    task.onlyIf(spec -> {
                        ClojureCompileTask self = (ClojureCompileTask) spec;
                        return !self.getNamespaces().get().isEmpty()
                                || !self.getMixinEdnFiles().isEmpty();
                    });
                });

        // Registers the AOT classes as main output: `jar` packs them and
        // every consumer of the source set output waits for compileClojure.
        main.getOutput().dir(Map.of("builtBy", compileClojure), clojureClasses);

        registerMixinConfig(project, extension, compileClojure, clojureClasses);
    }

    /**
     * The last step of the mixins-as-data pipeline: the generated
     * {@code <modid>.skein-mixins.json} must be listed in fabric.mod.json,
     * and the author never writes it there — a dedicated task patches the
     * *processed* copy after both processResources and compileClojure ran,
     * before anything consumes the classes (jar, dev runs, tests).
     */
    private void registerMixinConfig(
            Project project,
            SkeinExtension extension,
            TaskProvider<ClojureCompileTask> compileClojure,
            Provider<Directory> clojureClasses) {
        TaskProvider<ProcessResources> processResources =
                project.getTasks().named(JavaPlugin.PROCESS_RESOURCES_TASK_NAME, ProcessResources.class);
        TaskProvider<MixinModJsonTask> mixinModJson = project.getTasks()
                .register("skeinMixinModJson", MixinModJsonTask.class, task -> {
                    task.setDescription("Registers the generated Skein mixin config in the processed fabric.mod.json.");
                    task.dependsOn(compileClojure, processResources);
                    task.getClassesDir().set(clojureClasses);
                    task.getConfigFileName().set(extension.getModId().map(id -> id + ".skein-mixins.json"));
                    task.getFabricModJson()
                            .fileProvider(
                                    processResources.map(pr -> new File(pr.getDestinationDir(), "fabric.mod.json")));
                });
        project.getTasks().named(JavaPlugin.CLASSES_TASK_NAME).configure(classes -> classes.dependsOn(mixinModJson));
    }

    private void banBundlingAdapterProvidedJars(Project project) {
        project.getConfigurations()
                .matching(configuration -> INCLUDE_CONFIGURATION.equals(configuration.getName()))
                .all(include -> include.getDependencies().all(dependency -> {
                    String coordinate = dependency.getGroup() + ":" + dependency.getName();
                    if (ADAPTER_PROVIDED.contains(coordinate)) {
                        throw new GradleException("Skein: " + project.getPath() + " must not bundle (JiJ) '"
                                + coordinate + "'. The Skein adapter already ships it for the whole JVM and owns"
                                + " its version; a mod-bundled copy would fight the adapter's classloading."
                                + " Remove include(\"" + coordinate + "\") — a plain implementation dependency"
                                + " is fine and is added by this plugin automatically.");
                    }
                }));
    }

    private static String findModId(SourceSet main) {
        return main.getResources().getSrcDirs().stream()
                .map(dir -> new File(dir, "fabric.mod.json"))
                .map(ClojureNamespaces::parseModId)
                .flatMap(java.util.Optional::stream)
                .findFirst()
                .orElse(null);
    }
}
