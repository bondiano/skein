package skein.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;

/**
 * Registers the generated Skein mixin config in the *processed*
 * fabric.mod.json — the copy in the build output that the jar packs and
 * dev runs read. The author's source file never mentions the config:
 * declaring mixins (defmixin or mixins.edn) is all a mod does; the
 * registration is this task's job.
 *
 * <p>Runs after both processResources (the file to patch) and
 * compileClojure (whether a config was generated at all). It edits
 * another task's output, so it opts out of up-to-date checks entirely —
 * the patch itself is cheap and idempotent.
 */
public abstract class MixinModJsonTask extends DefaultTask {

    /** The processed fabric.mod.json (processResources output). */
    @Internal
    public abstract RegularFileProperty getFabricModJson();

    /** The Clojure compile path, where the pipeline drops the mixin config. */
    @Internal
    public abstract DirectoryProperty getClassesDir();

    /** {@code <modid>.skein-mixins.json}; absent when there is no mod id. */
    @Internal
    public abstract Property<String> getConfigFileName();

    public MixinModJsonTask() {
        getOutputs().upToDateWhen(task -> false);
    }

    @TaskAction
    void registerConfig() {
        if (!getConfigFileName().isPresent()) {
            return;
        }
        String configName = getConfigFileName().get();
        File config = new File(getClassesDir().get().getAsFile(), configName);
        File modJson = getFabricModJson().get().getAsFile();
        if (!config.isFile() || !modJson.isFile()) {
            return;
        }
        try {
            String patched =
                    MixinModJson.withConfig(Files.readString(modJson.toPath(), StandardCharsets.UTF_8), configName);
            if (patched != null) {
                Files.writeString(modJson.toPath(), patched, StandardCharsets.UTF_8);
                getLogger().info("Skein: registered mixin config {} in {}", configName, modJson);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to register the Skein mixin config in " + modJson, e);
        }
    }
}
