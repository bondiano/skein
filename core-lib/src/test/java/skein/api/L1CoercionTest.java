package skein.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bootstrap for the L1 foundation of the FP API layer (skein.id, skein.pos,
 * skein.text and their coercion protocols). The tests are clojure.test
 * deftests living next to the code; this class only runs them under JUnit.
 *
 * <p>They construct real game types (Identifier, BlockPos, Vec3, Component)
 * without booting the game, so the deobfuscated Minecraft jar and its
 * libraries are on the test runtime classpath — see this module's build.
 */
class L1CoercionTest {

    private static final List<String> NAMESPACES = List.of("skein.id-test", "skein.pos-test", "skein.text-test");

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
