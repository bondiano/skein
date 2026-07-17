package skein.gradle;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/**
 * AOT-compiles Clojure namespaces via {@code clojure.lang.Compile} in a
 * forked JVM (mods ship {@code .class} files, never {@code .clj}).
 *
 * <p>Also hosts the two lints, because both fire during/around compilation:
 * the reflection perf-lint parses the compiler's
 * {@code *warn-on-reflection*} output, and the ns-naming lint checks the
 * namespaces about to be compiled.
 */
public abstract class ClojureCompileTask extends JavaExec {

    private static final Set<String> REFLECTION_MODES = Set.of("warn", "error", "off");

    /** The Clojure source tree — an input for up-to-date checks only. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getClojureSources();

    @Input
    public abstract ListProperty<String> getNamespaces();

    @OutputDirectory
    public abstract DirectoryProperty getDestinationDirectory();

    /** {@code "warn"} | {@code "error"} | {@code "off"} — see {@link SkeinExtension#getReflectionWarnings()}. */
    @Input
    public abstract Property<String> getReflectionWarnings();

    @Input
    @Optional
    public abstract Property<String> getModId();

    @Input
    public abstract Property<Boolean> getNsLint();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    public ClojureCompileTask() {
        getMainClass().set("clojure.lang.Compile");
    }

    @Override
    public void exec() {
        String reflectionMode = getReflectionWarnings().get();
        if (!REFLECTION_MODES.contains(reflectionMode)) {
            throw new GradleException(
                    "skein.reflectionWarnings must be one of " + REFLECTION_MODES + ", got '" + reflectionMode + "'");
        }
        lintNamespaces();

        // Start from a clean compile path: it sits on the compile classpath
        // (clojure.lang.Compile requires that), and stale classes newer than
        // their source would be silently loaded instead of recompiled —
        // masking compile errors and reflection warnings alike.
        File destination = getDestinationDirectory().get().getAsFile();
        getFileSystemOperations().delete(spec -> spec.delete(destination));
        if (!destination.mkdirs()) {
            throw new GradleException("Could not create Clojure compile path " + destination);
        }
        setArgs(getNamespaces().get());
        systemProperty("clojure.compile.path", destination.getAbsolutePath());
        systemProperty("clojure.compile.warn-on-reflection", String.valueOf(!"off".equals(reflectionMode)));

        // The compiler prints reflection warnings to stderr; tee it so they
        // stay visible in the console and countable afterwards.
        ByteArrayOutputStream capturedStderr = new ByteArrayOutputStream();
        setErrorOutput(new TeeOutputStream(System.err, capturedStderr));
        super.exec();

        long warnings = ClojureNamespaces.countReflectionWarnings(capturedStderr.toString(StandardCharsets.UTF_8));
        if (warnings > 0) {
            String summary = warnings + " reflection warning(s) in Clojure sources (see compiler output above)."
                    + " Reflection is correct in production on MC 26.1+, but slow in hot paths — add type hints"
                    + " where it matters.";
            if ("error".equals(reflectionMode)) {
                throw new GradleException(
                        "Skein: " + summary + " Failing because skein.reflectionWarnings is set to \"error\".");
            }
            getLogger().warn("Skein: {}", summary);
        }
    }

    private void lintNamespaces() {
        if (!getNsLint().get() || !getModId().isPresent()) {
            return;
        }
        String modId = getModId().get();
        List<String> violations =
                ClojureNamespaces.nsLintViolations(modId, getNamespaces().get());
        if (!violations.isEmpty()) {
            getLogger()
                    .warn(
                            "Skein: namespace root should be the mod id '{}' — all Clojure mods share one namespace"
                                    + " registry, so a foreign root risks collisions. Offending namespaces: {}."
                                    + " (Rename them, or silence this with skein.nsLint = false.)",
                            modId,
                            String.join(", ", violations));
        }
    }

    /** Duplicates writes to both streams; never closes them (System.err must stay open). */
    private static final class TeeOutputStream extends OutputStream {

        private final OutputStream first;
        private final OutputStream second;

        TeeOutputStream(OutputStream first, OutputStream second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public void write(int b) throws IOException {
            first.write(b);
            second.write(b);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            first.write(bytes, offset, length);
            second.write(bytes, offset, length);
        }

        @Override
        public void flush() throws IOException {
            first.flush();
            second.flush();
        }
    }
}
