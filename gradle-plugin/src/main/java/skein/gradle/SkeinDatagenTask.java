package skein.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.work.DisableCachingByDefault;

/**
 * Runs the Skein datagen pipeline ({@code skein.data.datagen}) in a forked
 * JVM to turn the mod's {@code skein-data.edn} into generated resources
 * (lang, tags, recipes, loot, advancements) before {@code jar}.
 *
 * <p>Like {@link ClojureCompileTask} it is a {@link JavaExec} against the
 * game runtime classpath — the fork bootstraps vanilla to check every id a
 * tag/recipe references against the real registries, and loads the mod's
 * namespaces to learn the mod's own content ids. The generated directory is
 * registered as a main source-set output, so {@code jar} and dev runs pick
 * the files up with no author wiring.
 */
@DisableCachingByDefault(because = "Forked JVM datagen with a game bootstrap; not worth the cache overhead")
public abstract class SkeinDatagenTask extends JavaExec {

    /** The mod's {@code skein-data.edn} files (usually zero or one) — an input for up-to-date checks. */
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getDataFiles();

    /** The Clojure source tree — content declarations affect the generated ids. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClojureSources();

    /** The mod namespaces loaded (under collect-only) to gather the mod's content ids. */
    @Input
    public abstract ListProperty<String> getNamespaces();

    @Input
    @Optional
    public abstract Property<String> getModId();

    /** Resource roots scanned for {@code skein-data.edn}. */
    @Internal
    public abstract ConfigurableFileCollection getResourceDirs();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    public SkeinDatagenTask() {
        getMainClass().set("clojure.main");
    }

    @Override
    public void exec() {
        // Start clean: a removed lang key or recipe must not leave a stale file
        // behind in the packed jar.
        File out = getOutputDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(out));
        if (!out.mkdirs()) {
            throw new GradleException("Skein: could not create the datagen output directory " + out
                    + " — check filesystem permissions and that no file exists at that path.");
        }

        List<String> args = new ArrayList<>(List.of("-m", "skein.data.datagen"));
        args.addAll(getNamespaces().get());
        setArgs(args);

        systemProperty("skein.data.out", out.getAbsolutePath());
        if (getModId().isPresent()) {
            systemProperty("skein.data.modid", getModId().get());
        }
        systemProperty(
                "skein.data.resources",
                getResourceDirs().getFiles().stream()
                        .map(File::getAbsolutePath)
                        .collect(Collectors.joining(File.pathSeparator)));

        super.exec();
    }
}
