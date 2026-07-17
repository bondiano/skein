package skein.example;

import clojure.lang.RT;
import java.util.Collection;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The only Java in the test suite: fabric-loader-junit boots the real loader
 * (Knot classloading, mod discovery, language adapters) around JUnit test
 * classes, so one class has to exist on the Java side. It boots the game and
 * runs the example mod's entrypoint in {@code @BeforeAll} — the production
 * phase order — and then hands off to clojure.test: the harness namespace
 * turns each listed test namespace into a container of dynamic tests, one
 * per {@code deftest}. All test logic lives in the Clojure namespaces.
 */
class ClojureTests {

    @BeforeAll
    static void bootGameAndInitMod() {
        // Vanilla bootstrap first (fabric-api's registry-sync mixin redirects
        // the freeze out of it), then the mod entrypoint registers into the
        // still-open registries. getEntrypoint() is where the Clojure adapter
        // runs: it boots the shared runtime, requires the AOT-compiled ns and
        // wraps the fn into a SAM proxy.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        FabricLoader.getInstance().getEntrypointContainers("main", ModInitializer.class).stream()
                .filter(c -> c.getProvider().getMetadata().getId().equals("skein_example"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no 'main' entrypoint container for skein_example"))
                .getEntrypoint()
                .onInitialize();
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Collection<DynamicNode> clojureSuites() {
        skein.runtime.ClojureRuntime.get().requireNamespace("skein-example-test.harness");
        return (Collection<DynamicNode>) RT.var("skein-example-test.harness", "suites")
                .invoke(List.of(
                        "skein-example-test.loader-test",
                        "skein-example-test.content-test",
                        "skein-example-test.repl-test"));
    }
}
