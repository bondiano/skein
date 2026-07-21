package skein.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Runs the build-time datagen layer's unit tests (skein.data.json / ids /
 * schema and the resource generators). Like {@link SchemaLayerTest} these
 * deftests are pure data — the generators emit JSON text and reference lists
 * without any game types — so they need neither the Minecraft jar nor a booted
 * game; this class only drives them under JUnit. The id check against the real
 * registries lives in the integration test that boots the game.
 */
class DataLayerTest {

    private static final List<String> NAMESPACES = List.of(
            "skein.data.json-test",
            "skein.data.ids-test",
            "skein.data.schema-test",
            "skein.data.generators-test");

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
