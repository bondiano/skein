package skein.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bootstrap for the effects-as-data layer (skein.fx, skein.events/on-pure!) and
 * the command DSL (skein.command). The tests are clojure.test deftests living
 * next to the code; this class only runs them under JUnit.
 *
 * <p>The effect registry and command normalization are pure data logic, but the
 * command namespace links against Minecraft and Brigadier types (Commands,
 * CommandSourceStack, the argument types), so the deobfuscated Minecraft jar and
 * its libraries are on the test runtime classpath — see this module's build.
 */
class EffectsCommandTest {

    private static final List<String> NAMESPACES = List.of("skein.fx-test", "skein.events-test", "skein.command-test");

    @Test
    void clojureTestSuitePasses() {
        IFn require = Clojure.var("clojure.core", "require");
        for (String namespace : NAMESPACES) {
            require.invoke(Clojure.read(namespace));
        }
        IFn runTests = Clojure.var("clojure.test", "run-tests");
        Map<?, ?> summary = (Map<?, ?>) runTests.applyTo(
                clojure.lang.RT.seq(NAMESPACES.stream().map(Clojure::read).toList()));
        assertEquals(0L, ((Number) summary.get(Keyword.intern("fail"))).longValue(), "failing assertions");
        assertEquals(0L, ((Number) summary.get(Keyword.intern("error"))).longValue(), "thrown errors");
    }
}
