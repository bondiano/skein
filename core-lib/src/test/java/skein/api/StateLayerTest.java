package skein.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bootstrap for the state layer: the schema-derived codec (skein.codec) and mod
 * state (skein.state). The codec tests build real NBT tags — that is plain
 * serialization code off the Minecraft jar, no booted game — and the state tests
 * cover declaration, the hot-reload contract and the errors a bad declaration
 * produces. Writing state into a world save, and attaching data to entities and
 * chunks, need a running server and live in the integration test.
 */
class StateLayerTest {

    private static final List<String> NAMESPACES = List.of("skein.codec-test", "skein.state-test");

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
