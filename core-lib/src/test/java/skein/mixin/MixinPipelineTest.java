package skein.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bootstrap for the Clojure test namespaces of the mixin build pipeline
 * (target resolution, malli schema, ASM codegen, the defmixin macro).
 * The tests themselves are clojure.test deftests living next to the
 * code they exercise; this class only runs them under JUnit and fails
 * on any failure/error in the summary.
 */
class MixinPipelineTest {

    private static final List<String> NAMESPACES = List.of(
            "skein.mixin.resolve-test",
            "skein.mixin.schema-test",
            "skein.mixin.codegen-test",
            "skein.mixin.defmixin-test");

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
