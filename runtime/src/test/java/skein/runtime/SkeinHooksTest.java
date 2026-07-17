package skein.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SkeinHooksTest {

    @Test
    void callsVarWithArguments() {
        Object result = SkeinHooks.call("skein-hooks-fixture.core/echo", "a", 1, null);
        assertEquals(java.util.Arrays.asList("a", 1, null), result);
    }

    @Test
    void callsZeroArityVar() {
        Object result = SkeinHooks.call("skein-hooks-fixture.core/echo");
        assertEquals(List.of(), result);
    }

    @Test
    void redefiningTheVarChangesBehaviourOfTheCachedHook() {
        clojure.lang.IDeref counter = (clojure.lang.IDeref)
                clojure.java.api.Clojure.var("clojure.core", "atom").invoke(0L);
        Object before = SkeinHooks.call("skein-hooks-fixture.core/tick-handler", counter);
        assertEquals("original", ((clojure.lang.Keyword) before).getName());

        SkeinHooks.call("skein-hooks-fixture.core/redefine!");

        Object after = SkeinHooks.call("skein-hooks-fixture.core/tick-handler", counter);
        assertEquals("redefined", ((clojure.lang.Keyword) after).getName());
    }

    @Test
    void missingVarListsPublicVars() {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> SkeinHooks.call("skein-hooks-fixture.core/nope"));
        assertTrue(e.getMessage().contains("no bound var 'nope'"), e.getMessage());
        assertTrue(e.getMessage().contains("echo"), "should list public vars: " + e.getMessage());
    }

    @Test
    void missingNamespaceFailsFast() {
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> SkeinHooks.call("skein-no-such.ns/handler"));
        assertTrue(e.getMessage().contains("cannot load namespace"), e.getMessage());
    }

    @Test
    void unqualifiedNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SkeinHooks.call("not-qualified"));
        assertThrows(IllegalArgumentException.class, () -> SkeinHooks.call("ns/"));
    }

    @Test
    void hookDefinedAfterAFailedCallStartsWorking() {
        String hook = "skein-hooks-fixture.core/late-" + AtomicLong.class.hashCode();
        assertThrows(IllegalStateException.class, () -> SkeinHooks.call(hook));

        clojure.java.api.Clojure.var("clojure.core", "intern")
                .invoke(
                        clojure.java.api.Clojure.read("skein-hooks-fixture.core"),
                        clojure.java.api.Clojure.read(hook.substring(hook.indexOf('/') + 1)),
                        clojure.java.api.Clojure.var("clojure.core", "identity"));

        assertEquals("late", SkeinHooks.call(hook, "late"));
    }
}
