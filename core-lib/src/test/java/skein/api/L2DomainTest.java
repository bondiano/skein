package skein.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bootstrap for the L2 domain layer (skein.block/item/world/entity/player/
 * inventory/schedule and the effects they wire into skein.fx). The tests are
 * clojure.test deftests living next to the code; this class only runs them under
 * JUnit.
 *
 * <p>Block coercion and the schedule timers run headlessly here; loading the
 * remaining domain namespaces links their code against the real Minecraft jar (a
 * smoke test of the API surface). The world/entity/player mutations themselves
 * need a live server and are exercised in the integration environment.
 */
class L2DomainTest {

    private static final List<String> NAMESPACES =
            List.of("skein.block-test", "skein.schedule-test", "skein.l2-wiring-test", "skein.inspect-test");

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
