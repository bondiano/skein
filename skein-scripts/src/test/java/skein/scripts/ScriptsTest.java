package skein.scripts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bootstrap for the Skein Scripts Clojure test namespaces (config parsing
 * and the runtime script loader). They are clojure.test deftests living
 * next to the code they exercise; this class runs them under JUnit and
 * fails on any failure or error in the summary. The loader tests are
 * game-agnostic: they load scripts from a temp directory in the plain test
 * JVM, no Fabric loader involved.
 */
class ScriptsTest {

    private static final List<String> NAMESPACES = List.of("skein-scripts.config-test", "skein-scripts.loader-test");

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
