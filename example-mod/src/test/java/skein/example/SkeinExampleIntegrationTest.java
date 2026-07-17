package skein.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import clojure.lang.IDeref;
import clojure.lang.RT;
import java.lang.reflect.Proxy;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.junit.jupiter.api.Test;

/**
 * Integration test in a headless Fabric dev env: fabric-loader-junit boots
 * the real loader (Knot classloading, mod discovery, language adapters)
 * inside this JVM, so the whole chain is exercised — fabric.mod.json of the
 * adapter registers the {@code "clojure"} language adapter, and this mod's
 * {@code "skein-example.core/init"} entrypoint resolves through it into an
 * AOT-compiled Clojure fn.
 */
class SkeinExampleIntegrationTest {

    @Test
    void loaderDiscoversAdapterAndExampleMod() {
        FabricLoader loader = FabricLoader.getInstance();
        assertTrue(loader.isModLoaded("skein"), "adapter mod discovered");
        assertTrue(loader.isModLoaded("skein_example"), "example mod discovered");
    }

    @Test
    void clojureMainEntrypointResolvesAndRuns() {
        EntrypointContainer<ModInitializer> container =
                FabricLoader.getInstance().getEntrypointContainers("main", ModInitializer.class).stream()
                        .filter(c -> c.getProvider().getMetadata().getId().equals("skein_example"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no 'main' entrypoint container for skein_example"));

        // getEntrypoint() is where the Clojure adapter runs: boots the shared
        // runtime, requires the AOT-compiled ns and wraps the fn into a proxy.
        ModInitializer initializer = container.getEntrypoint();
        assertTrue(Proxy.isProxyClass(initializer.getClass()), "fn wrapped into a SAM proxy");

        initializer.onInitialize();
        Object flag = ((IDeref) RT.var("skein-example.core", "initialized?").deref()).deref();
        assertEquals(Boolean.TRUE, flag, "Clojure init fn actually ran");
    }
}
