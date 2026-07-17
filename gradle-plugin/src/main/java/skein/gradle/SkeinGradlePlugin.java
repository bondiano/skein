package skein.gradle;

import java.io.File;
import java.util.Map;
import java.util.Set;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

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

        project.getPlugins().withType(JavaPlugin.class, javaPlugin -> configureClojureBuild(project, extension));
        banBundlingAdapterProvidedJars(project);
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
                    // clojure.lang.Compile requires the sources and the compile
                    // path itself on the classpath, plus the mod's own Java
                    // classes (interop stubs) and the runtime dependencies.
                    task.classpath(
                            clojureSrc,
                            clojureClasses,
                            main.getOutput().getClassesDirs(),
                            project.getConfigurations().getByName(main.getRuntimeClasspathConfigurationName()));
                    task.onlyIf(spec ->
                            !((ClojureCompileTask) spec).getNamespaces().get().isEmpty());
                });

        // Registers the AOT classes as main output: `jar` packs them and
        // every consumer of the source set output waits for compileClojure.
        main.getOutput().dir(Map.of("builtBy", compileClojure), clojureClasses);
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
