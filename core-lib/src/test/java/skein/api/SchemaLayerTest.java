package skein.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bootstrap for the boundary-validation and logging layer (skein.schema,
 * skein.schemas, skein.log). These clojure.test deftests are pure data and
 * Malli / tools.logging only — no game types — so unlike {@link L1CoercionTest}
 * they need neither the Minecraft jar nor a booted game; this class only runs
 * them under JUnit.
 */
class SchemaLayerTest {

    private static final List<String> NAMESPACES = List.of("skein.schema-test", "skein.schemas-test", "skein.log-test");

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
