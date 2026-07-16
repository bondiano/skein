package skein.adapter;

import net.fabricmc.loader.api.LanguageAdapter;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;

/**
 * Fabric language adapter for Clojure.
 *
 * <p>Initializes the single shared Clojure runtime lazily, on the first
 * Clojure entrypoint (DESIGN.md §4.1), then resolves {@code ns/var} and
 * {@code ns} entrypoint values into entrypoint instances (§5.1), wrapping
 * SAM interfaces via {@code java.lang.reflect.Proxy} over {@code IFn}
 * with a var deref on every call (§5.2).
 */
public final class ClojureLanguageAdapter implements LanguageAdapter {

    @Override
    public <T> T create(ModContainer mod, String value, Class<T> type) throws LanguageAdapterException {
        // M0: bootstrap Clojure RT inside the Knot classloader and resolve the entrypoint var.
        throw new LanguageAdapterException(
                "Skein is a work-in-progress skeleton: entrypoint resolution lands in M0/M1 "
                        + "(mod '" + mod.getMetadata().getId() + "', value '" + value + "')");
    }
}
