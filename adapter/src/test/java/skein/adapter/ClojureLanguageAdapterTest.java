package skein.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clojure.lang.Keyword;
import clojure.lang.RT;
import java.lang.reflect.Proxy;
import net.fabricmc.loader.api.LanguageAdapterException;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.junit.jupiter.api.Test;

/**
 * Entrypoint resolution against the fixture namespace in test resources
 * ({@code skein_test_mod/core.clj}).
 */
class ClojureLanguageAdapterTest {

    /** SAM interface for proxy wrapping. */
    public interface Greeter {
        String greet(String name);
    }

    /** SAM interface with no args. */
    public interface Entrypoint {
        Object call();
    }

    /** Not a SAM — a fn cannot implement it. */
    public interface MultiMethod {
        void first();

        void second();
    }

    private final ClojureLanguageAdapter adapter = new ClojureLanguageAdapter();
    private final ModContainer mod = stubMod("test-mod");

    @Test
    void nsVarFormWrapsFnIntoSamProxy() throws Exception {
        Greeter greeter = adapter.create(mod, "skein-test-mod.core/greet", Greeter.class);
        assertTrue(Proxy.isProxyClass(greeter.getClass()));
        assertEquals("Hello, world", greeter.greet("world"));
    }

    @Test
    void bareNsFormResolvesInitByConvention() throws Exception {
        Entrypoint entrypoint = adapter.create(mod, "skein-test-mod.core", Entrypoint.class);
        assertEquals(Keyword.intern("initialized"), entrypoint.call());
    }

    @Test
    void reifyInstanceIsReturnedAsIs() throws Exception {
        Runnable resolved = adapter.create(mod, "skein-test-mod.core/runner", Runnable.class);
        assertSame(RT.var("skein-test-mod.core", "runner").deref(), resolved);
    }

    @Test
    void proxyDerefsVarOnEveryCall() throws Exception {
        Entrypoint entrypoint = adapter.create(mod, "skein-test-mod.core/flip", Entrypoint.class);
        assertEquals(Keyword.intern("original"), entrypoint.call());
        RT.var("skein-test-mod.core", "flip!").invoke();
        assertEquals(Keyword.intern("redefined"), entrypoint.call());
    }

    @Test
    void missingNamespaceFailsWithLoadError() {
        LanguageAdapterException e = assertThrows(
                LanguageAdapterException.class,
                () -> adapter.create(mod, "skein-test-mod.nope/init", Entrypoint.class));
        assertTrue(e.getMessage().contains("Failed to load Clojure namespace 'skein-test-mod.nope'"), e.getMessage());
        assertTrue(e.getMessage().contains("test-mod"), e.getMessage());
    }

    @Test
    void missingVarListsPublicVars() {
        LanguageAdapterException e = assertThrows(
                LanguageAdapterException.class,
                () -> adapter.create(mod, "skein-test-mod.core/absent", Entrypoint.class));
        assertTrue(e.getMessage().contains("has no var 'absent'"), e.getMessage());
        assertTrue(e.getMessage().contains("greet"), e.getMessage());
    }

    @Test
    void bareFormWithoutInitVarExplainsConvention() {
        ModContainer noInitMod = stubMod("no-init");
        // clojure.core has no `init` var and is always loadable.
        LanguageAdapterException e = assertThrows(
                LanguageAdapterException.class, () -> adapter.create(noInitMod, "clojure.core", Entrypoint.class));
        assertTrue(e.getMessage().contains("convention name for the bare-namespace form"), e.getMessage());
    }

    @Test
    void nonFnNonInstanceValueFails() {
        LanguageAdapterException e = assertThrows(
                LanguageAdapterException.class, () -> adapter.create(mod, "skein-test-mod.core/answer", Greeter.class));
        assertTrue(e.getMessage().contains("neither a function nor an instance"), e.getMessage());
        assertTrue(e.getMessage().contains("java.lang.Long"), e.getMessage());
    }

    @Test
    void fnAgainstMultiMethodInterfaceDemandsReify() {
        LanguageAdapterException e = assertThrows(
                LanguageAdapterException.class,
                () -> adapter.create(mod, "skein-test-mod.core/greet", MultiMethod.class));
        assertTrue(e.getMessage().contains("not a SAM interface"), e.getMessage());
        assertTrue(e.getMessage().contains("reify"), e.getMessage());
    }

    @Test
    void malformedValuesFailFast() {
        for (String value : new String[] {"", "  ", "ns/", "/var", "a/b/c", "my ns/init"}) {
            LanguageAdapterException e = assertThrows(
                    LanguageAdapterException.class, () -> adapter.create(mod, value, Entrypoint.class), value);
            assertTrue(e.getMessage().contains("Invalid Clojure entrypoint"), e.getMessage());
        }
    }

    /** Minimal ModContainer: the adapter only reads the mod id. */
    private static ModContainer stubMod(String id) {
        ModMetadata metadata = (ModMetadata) Proxy.newProxyInstance(
                ClojureLanguageAdapterTest.class.getClassLoader(),
                new Class<?>[] {ModMetadata.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getId")) {
                        return id;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        return (ModContainer) Proxy.newProxyInstance(
                ClojureLanguageAdapterTest.class.getClassLoader(),
                new Class<?>[] {ModContainer.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getMetadata")) {
                        return metadata;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
