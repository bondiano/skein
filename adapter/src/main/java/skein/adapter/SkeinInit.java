package skein.adapter;

import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Var;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skein.runtime.ClojureRuntime;

/**
 * The adapter's own {@code main} entrypoint: starts the dev nREPL server.
 *
 * <p>Dev only and on by default — the REPL out of the box is the point of
 * Skein. Production stays off until the opt-in production-REPL story (with
 * its security model) ships. Tunables (JVM properties, wired into Loom run
 * configs by the Skein Gradle plugin):
 *
 * <ul>
 *   <li>{@code skein.nrepl.port} — default 7888;</li>
 *   <li>{@code skein.nrepl.bind} — default {@code 127.0.0.1};</li>
 *   <li>{@code skein.nrepl.disabled} — set {@code true} to opt out;</li>
 *   <li>{@code skein.nrepl.game-thread-eval} — opt-in middleware running
 *       every eval on the game thread.</li>
 * </ul>
 *
 * <p>A REPL failure must never take the game down — errors are logged and
 * startup continues.
 */
public final class SkeinInit implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("skein");

    @Override
    public void onInitialize() {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            return;
        }
        if (Boolean.getBoolean("skein.nrepl.disabled")) {
            LOGGER.info("[skein] dev nREPL disabled via -Dskein.nrepl.disabled=true");
            return;
        }
        int port = Integer.getInteger("skein.nrepl.port", 7888);
        String bind = System.getProperty("skein.nrepl.bind", "127.0.0.1");
        boolean gameThreadEval = Boolean.getBoolean("skein.nrepl.game-thread-eval");
        try {
            ClojureRuntime runtime = ClojureRuntime.get();
            runtime.requireNamespace("skein.repl");
            Var start = runtime.resolveVar("skein.repl", "start!");
            start.invoke(RT.map(
                    Keyword.intern("port"), port,
                    Keyword.intern("bind"), bind,
                    Keyword.intern("game-thread-eval?"), gameThreadEval));
            LOGGER.info(
                    "[skein] dev nREPL server listening on {}:{}{} — connect with CIDER/Calva"
                            + " (opt out with -Dskein.nrepl.disabled=true)",
                    bind,
                    port,
                    gameThreadEval ? " (game-thread eval)" : "");
        } catch (Throwable t) {
            LOGGER.error("[skein] failed to start the dev nREPL server — continuing without a REPL", t);
        }
    }
}
