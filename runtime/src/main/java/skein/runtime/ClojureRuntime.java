package skein.runtime;

import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.lang.Symbol;
import clojure.lang.Var;
import java.lang.management.ManagementFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single shared Clojure runtime for the whole JVM.
 *
 * <p>Booted lazily on the first Clojure entrypoint. During boot and
 * every namespace load the thread context classloader is switched to the
 * classloader that loaded this class — under Fabric that is Knot — so
 * {@code RT.baseLoader()} resolves to Knot and Clojure's own
 * {@code DynamicClassLoader} instances parent on it. That is what lets
 * Clojure code see the game and other mods' classes.
 *
 * <p>MC 26.1+ is unobfuscated, so no mapping resolution is involved.
 */
public final class ClojureRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger("skein");

    private static final Object LOCK = new Object();
    private static volatile ClojureRuntime instance;

    private final IFn require;

    private ClojureRuntime(IFn require) {
        this.require = require;
    }

    /** Returns the shared runtime, booting it on first call. */
    public static ClojureRuntime get() {
        ClojureRuntime local = instance;
        if (local == null) {
            synchronized (LOCK) {
                local = instance;
                if (local == null) {
                    local = boot();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** True if the runtime has already been booted (never boots). */
    public static boolean isBooted() {
        return instance != null;
    }

    private static ClojureRuntime boot() {
        ClassLoader knot = ClojureRuntime.class.getClassLoader();
        long classesBefore = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
        long startNanos = System.nanoTime();

        IFn require = withContextClassLoader(
                knot,
                // First touch of clojure.lang.RT: static init loads clojure/core
                // through RT.baseLoader() = the context classloader (Knot).
                () -> RT.var("clojure.core", "require"));

        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        long classesLoaded = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount() - classesBefore;
        LOGGER.info(
                "[skein] Clojure {} runtime booted in {} ms ({} classes loaded, parent classloader: {})",
                RT.var("clojure.core", "clojure-version").invoke(),
                elapsedMillis,
                classesLoaded,
                knot);

        return new ClojureRuntime(require);
    }

    /**
     * Requires {@code namespace}, loading its AOT-compiled classes through
     * Knot. Loading and var lookup are separate steps so callers can produce
     * precise fail-fast diagnostics.
     */
    public void requireNamespace(String namespace) {
        ClassLoader knot = ClojureRuntime.class.getClassLoader();
        withContextClassLoader(knot, () -> require.invoke(Symbol.intern(namespace)));
    }

    /**
     * Returns the var named {@code varName} in an already-required
     * {@code namespace}, or {@code null} if no bound var is interned there.
     */
    public Var resolveVar(String namespace, String varName) {
        Var var = Var.find(Symbol.intern(namespace, varName));
        return (var == null || !var.isBound()) ? null : var;
    }

    /**
     * Names of the public vars of an already-required {@code namespace},
     * sorted — used to make "no such var" errors actionable.
     */
    public java.util.List<String> publicVarNames(String namespace) {
        IFn nsPublics = RT.var("clojure.core", "ns-publics");
        Object publics = nsPublics.invoke(Symbol.intern(namespace));
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Object entry : (Iterable<?>) publics) {
            names.add(((java.util.Map.Entry<?, ?>) entry).getKey().toString());
        }
        names.sort(null);
        return names;
    }

    /** Runs {@code body} with the given context classloader, restoring the previous one. */
    public static <T> T withContextClassLoader(ClassLoader loader, java.util.function.Supplier<T> body) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return body.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
