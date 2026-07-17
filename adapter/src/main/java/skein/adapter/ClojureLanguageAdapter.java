package skein.adapter;

import clojure.lang.IFn;
import clojure.lang.RT;
import clojure.lang.Var;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.List;
import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skein.runtime.ClojureRuntime;

/**
 * Fabric language adapter for Clojure.
 *
 * <p>Initializes the single shared Clojure runtime lazily, on the first
 * Clojure entrypoint, then resolves entrypoint values into entrypoint
 * instances. Two value forms are accepted:
 *
 * <ul>
 *   <li>{@code "ns/var"} — requires {@code ns}, derefs {@code var};</li>
 *   <li>{@code "ns"} — requires {@code ns}, resolves the var named
 *       {@code init} by convention. (The Loader API does not expose the
 *       entrypoint key to adapters, so a key-named-var convention is not
 *       implementable; {@code init} is the single convention.)</li>
 * </ul>
 *
 * <p>A var holding a function is wrapped into the target SAM interface via
 * {@link Proxy} over {@link IFn} with a var deref on every call — that deref
 * is what makes entrypoint logic hot-reloadable from the REPL. A var
 * holding an object that already implements the target interface (a
 * {@code reify} instance) is returned as-is; multi-method interfaces are
 * only supported this way.
 *
 * <p><b>{@code preLaunch} entrypoints</b> are supported: the runtime boots
 * whenever the first Clojure entrypoint runs, including pre-launch. The usual
 * Fabric restrictions apply and are stricter than for {@code main} — game and
 * mod classes must not be touched (mixins are not applied yet), so pre-launch
 * Clojure code should limit itself to pure setup (logging, instrumentation).
 * Note that booting Clojure at pre-launch moves its ~100ms init cost to the
 * earliest phase of startup.
 */
public final class ClojureLanguageAdapter implements LanguageAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger("skein");

    /** Convention var name for the bare-namespace entrypoint form. */
    private static final String CONVENTION_VAR = "init";

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        String modId = mod.getMetadata().getId();
        EntrypointRef ref = parse(value, modId);

        long startNanos = System.nanoTime();
        ClojureRuntime runtime = ClojureRuntime.get();
        try {
            runtime.requireNamespace(ref.namespace());
        } catch (Exception e) {
            throw new LanguageAdapterException(
                    "Failed to load Clojure namespace '" + ref.namespace() + "' for entrypoint '" + value
                            + "' of mod '" + modId + "': " + rootMessage(e)
                            + ". The namespace must be AOT-compiled onto the mod's classpath"
                            + " (Skein mods are AOT-only; the Skein Gradle plugin does this before `jar`).",
                    e);
        }

        Var var = runtime.resolveVar(ref.namespace(), ref.varName());
        if (var == null) {
            throw new LanguageAdapterException(noSuchVarMessage(runtime, mod, value, ref));
        }
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;
        LOGGER.info("[skein] resolved entrypoint {} of mod '{}' in {} ms", value, modId, elapsedMillis);

        Object derefed = var.deref();
        if (type.isInstance(derefed)) {
            // The var already implements the target interface (a reify instance).
            return type.cast(derefed);
        }
        if (derefed instanceof IFn && type.isInterface()) {
            List<Method> abstractMethods = abstractMethodsOf(type);
            if (abstractMethods.size() > 1) {
                throw new LanguageAdapterException("Clojure entrypoint '" + value + "' of mod '" + modId
                        + "' is a function, but "
                        + type.getName() + " is not a SAM interface — it has "
                        + abstractMethods.size() + " abstract methods ("
                        + String.join(
                                ", ",
                                abstractMethods.stream().map(Method::getName).toList())
                        + "). Point the entrypoint at a var holding a (reify " + type.getSimpleName()
                        + " ...) instance instead.");
            }
            return type.cast(Proxy.newProxyInstance(
                    ClojureLanguageAdapter.class.getClassLoader(),
                    new Class<?>[] {type},
                    new VarInvocationHandler(var, modId, value)));
        }
        throw new LanguageAdapterException("Clojure entrypoint '" + value + "' of mod '" + modId + "' resolves to "
                + (derefed == null
                        ? "nil"
                        : "an instance of " + derefed.getClass().getName())
                + ", which is neither a function nor an instance of the expected type "
                + type.getName() + ". Entrypoint vars must hold a fn (for SAM interfaces) or a"
                + " reify/proxy instance of the target interface.");
    }

    /** The two entrypoint value forms: {@code ns/var} and bare {@code ns}. */
    private record EntrypointRef(String namespace, String varName, boolean bareForm) {}

    private static EntrypointRef parse(String value, String modId) throws LanguageAdapterException {
        if (value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new LanguageAdapterException("Invalid Clojure entrypoint '" + value + "' in mod '" + modId
                    + "': expected \"namespace/var\" or \"namespace\", e.g. \"mymod.core/init\"");
        }
        int slash = value.indexOf('/');
        if (slash < 0) {
            return new EntrypointRef(value, CONVENTION_VAR, true);
        }
        if (slash == 0 || slash == value.length() - 1 || slash != value.lastIndexOf('/')) {
            throw new LanguageAdapterException("Invalid Clojure entrypoint '" + value + "' in mod '" + modId
                    + "': expected \"namespace/var\" or \"namespace\", e.g. \"mymod.core/init\"");
        }
        return new EntrypointRef(value.substring(0, slash), value.substring(slash + 1), false);
    }

    private static String noSuchVarMessage(ClojureRuntime runtime, ModContainer mod, String value, EntrypointRef ref) {
        StringBuilder message = new StringBuilder()
                .append("Clojure entrypoint '")
                .append(value)
                .append("' of mod '")
                .append(mod.getMetadata().getId())
                .append("': namespace '")
                .append(ref.namespace())
                .append("' loaded, but has no var '")
                .append(ref.varName())
                .append("'");
        if (ref.bareForm()) {
            message.append(" ('")
                    .append(CONVENTION_VAR)
                    .append("' is the convention name for the bare-namespace form; use \"")
                    .append(ref.namespace())
                    .append("/your-var\" to name a different var)");
        }
        List<String> publics = runtime.publicVarNames(ref.namespace());
        if (publics.isEmpty()) {
            message.append(". The namespace defines no public vars.");
        } else {
            message.append(". Public vars in this namespace: ")
                    .append(String.join(", ", publics))
                    .append('.');
        }
        return message.toString();
    }

    /** Abstract methods a proxy must implement, minus those every Object already has. */
    private static List<Method> abstractMethodsOf(Class<?> type) {
        return java.util.Arrays.stream(type.getMethods())
                .filter(m -> Modifier.isAbstract(m.getModifiers()))
                .filter(m -> !overridesObjectMethod(m))
                .toList();
    }

    private static boolean overridesObjectMethod(Method method) {
        try {
            Object.class.getMethod(method.getName(), method.getParameterTypes());
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.toString();
    }

    /**
     * Dispatches every interface method to the var's current value,
     * dereferencing on each call so a REPL {@code defn} redefinition takes
     * effect immediately. This per-call deref is the feature, not an
     * inefficiency.
     */
    private record VarInvocationHandler(Var var, String modId, String entrypoint) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "skein entrypoint proxy " + entrypoint + " (mod '" + modId + "')";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            Object current = var.deref();
            if (!(current instanceof IFn fn)) {
                throw new IllegalStateException("Clojure entrypoint '" + entrypoint + "' of mod '" + modId
                        + "' is no longer a function: var " + var + " now holds "
                        + (current == null ? "nil" : current.getClass().getName()));
            }
            return fn.applyTo(RT.seq(args));
        }
    }
}
