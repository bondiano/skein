package skein.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clojure.lang.Keyword;
import clojure.lang.RT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Dev-REPL end to end in the headless Fabric env: the nREPL server from the
 * repl module starts in this JVM, a client connects over the real nREPL
 * protocol (exactly what CIDER/Calva speak) and everything is asserted
 * through eval messages — including interop with loader classes, re-def of
 * a var, add-lib from Clojars and the opt-in game-thread middleware. The
 * checks themselves live in {@code skein_example_test/repl_check.clj}.
 */
class ReplIntegrationTest {

    @Test
    void devReplEndToEnd() {
        Map<?, ?> r = runCheck("run-checks");
        assertEquals(List.of("3"), r.get(kw("arith")), "plain eval");
        assertEquals(List.of("\"SKEIN\""), r.get(kw("interop")), "java interop");
        assertEquals(List.of("\"skein\""), r.get(kw("loader-interop")), "interop with loader classes");
        assertEquals(List.of(":redefined"), r.get(kw("redef")), "re-def of a var in a live session");
        assertEquals(List.of("\"{\\\"a\\\":1}\""), r.get(kw("add-lib")), "lib added from Clojars is usable");
    }

    @Test
    void gameThreadMiddlewareDispatchesEval() {
        Map<?, ?> r = runCheck("run-game-thread-check");
        assertEquals(List.of("\"fake-game-thread\""), r.get(kw("eval-thread")));
    }

    private static Map<?, ?> runCheck(String checkFn) {
        skein.runtime.ClojureRuntime.get().requireNamespace("skein-example-test.repl-check");
        return (Map<?, ?>) RT.var("skein-example-test.repl-check", checkFn).invoke();
    }

    private static Keyword kw(String name) {
        return Keyword.intern(name);
    }
}
