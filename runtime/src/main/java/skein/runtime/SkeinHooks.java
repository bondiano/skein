package skein.runtime;

import clojure.lang.ArraySeq;
import clojure.lang.Var;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridge from Java mixin stubs into Clojure handlers.
 *
 * <p>The v1 mixin story: the mixin class is a few lines of Java (annotations
 * plus one static hook call), the logic lives in a Clojure fn:
 *
 * <pre>{@code
 * @Mixin(MinecraftServer.class)
 * public class ServerMixin {
 *     @Inject(method = "tickServer", at = @At("HEAD"))
 *     private void onTick(BooleanSupplier b, CallbackInfo ci) {
 *         SkeinHooks.call("mymod.core/on-server-tick", this, b, ci);
 *     }
 * }
 * }</pre>
 *
 * <p>The lookup caches the {@link Var} object, never its value: a Var
 * invocation dereferences its current binding on every call, so redefining
 * the fn from the REPL changes the mixin's behaviour immediately — the same
 * indirection the adapter uses for entrypoints.
 *
 * <p>Resolution failures are not cached: a hook whose namespace or var does
 * not exist yet starts working the moment it is defined (e.g. from a REPL
 * session), at the cost of re-throwing on every call until then.
 */
public final class SkeinHooks {

    private static final ConcurrentHashMap<String, Var> CACHE = new ConcurrentHashMap<>();

    private SkeinHooks() {}

    /**
     * Invokes the Clojure fn bound to {@code qualifiedName} (e.g.
     * {@code "mymod.core/on-server-tick"}) with {@code args}, loading the
     * namespace on first use, and returns the fn's result.
     */
    public static Object call(String qualifiedName, Object... args) {
        Var var = CACHE.computeIfAbsent(qualifiedName, SkeinHooks::resolve);
        return var.applyTo(ArraySeq.create(args));
    }

    private static Var resolve(String qualifiedName) {
        int slash = qualifiedName.indexOf('/');
        if (slash <= 0 || slash == qualifiedName.length() - 1) {
            throw new IllegalArgumentException("[skein] Invalid hook name '" + qualifiedName
                    + "': expected a namespace-qualified var like \"mymod.core/on-server-tick\"");
        }
        String namespace = qualifiedName.substring(0, slash);
        String name = qualifiedName.substring(slash + 1);

        ClojureRuntime runtime = ClojureRuntime.get();
        try {
            runtime.requireNamespace(namespace);
            // Clojure's require sneaky-throws checked FileNotFoundException
            // for a missing namespace — catch broadly.
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[skein] Hook '" + qualifiedName + "': cannot load namespace '" + namespace
                            + "'. Is the namespace AOT-compiled into the mod jar and free of load-time errors?",
                    e);
        }
        Var var = runtime.resolveVar(namespace, name);
        if (var == null) {
            throw new IllegalStateException("[skein] Hook '" + qualifiedName + "': no bound var '" + name
                    + "' in namespace '" + namespace + "'. Public vars there: "
                    + String.join(", ", runtime.publicVarNames(namespace)));
        }
        return var;
    }
}
