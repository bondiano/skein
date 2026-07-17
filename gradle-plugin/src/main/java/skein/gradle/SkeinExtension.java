package skein.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * {@code skein { ... }} build-script configuration.
 *
 * <p>Every property has a working convention — a mod builds with an empty
 * block. All lints follow the fail-fast-but-not-hostile policy: warnings by
 * default, errors strictly opt-in.
 */
public abstract class SkeinExtension {

    public static final String NAME = "skein";

    /**
     * The mod id used by the ns-naming lint. Convention: parsed from
     * {@code fabric.mod.json} in the main resources; the lint is skipped if
     * neither yields an id.
     */
    public abstract Property<String> getModId();

    /**
     * Namespaces to AOT-compile. Convention: every {@code .clj}/{@code .cljc}
     * file under {@code src/main/clojure}, path mapped to a ns name
     * ({@code _} → {@code -}). Transitively required namespaces are compiled
     * with them (that is how {@code clojure.lang.Compile} works).
     */
    public abstract ListProperty<String> getAotNamespaces();

    /**
     * Reflection perf-lint mode: {@code "warn"} (default),
     * {@code "error"}, or {@code "off"}. On MC 26.1+ reflection is correct in
     * production — this is purely about hot-path performance.
     */
    public abstract Property<String> getReflectionWarnings();

    /** Ns-naming lint ({@code <modid>.*} root): warning; default on. */
    public abstract Property<Boolean> getNsLint();

    /**
     * Port of the dev nREPL server; wired as {@code skein.nrepl.port} into
     * every Loom run config. Default 7888.
     */
    public abstract Property<Integer> getNreplPort();
}
