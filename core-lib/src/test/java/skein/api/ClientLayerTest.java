package skein.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Bootstrap for the GUI wave (stage C): the client-side layer (skein.client.*)
 * — client event wrappers, keybindings-as-data, the draw layer, HUD overlays and
 * simple screens — plus the server-side menu layer (skein.menu). These build
 * game objects (a KeyMapping, a Button, a container) from the merged Minecraft
 * jar but need no running client. Registering with Fabric's client events,
 * rendering a real frame, and opening a menu for a connected player need a live
 * client / server and live in a manual / L2 / client-gametest check.
 */
class ClientLayerTest {

    private static final List<String> NAMESPACES = List.of(
            "skein.client.events-test",
            "skein.client.keys-test",
            "skein.client.draw-test",
            "skein.client.hud-test",
            "skein.client.screen-test",
            "skein.menu-test");

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
