package skein.adapter;

import clojure.lang.Keyword;
import clojure.lang.RT;
import clojure.lang.Var;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import skein.runtime.ClojureRuntime;

/**
 * The adapter's own {@code main} entrypoint: starts the nREPL server.
 *
 * <p>Dev: on by default — the REPL out of the box is the point of Skein
 * (opt out with {@code -Dskein.nrepl.disabled=true}). Production: strictly
 * opt-in, loopback-only, with a loud warning on activation — an nREPL
 * session is full control of the JVM. Settings come from JVM properties
 * and {@code config/skein/nrepl.properties}; see {@link NreplConfig} for
 * the keys and precedence.
 *
 * <p>A REPL failure must never take the game down — errors are logged and
 * startup continues.
 */
public final class SkeinInit implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("skein");

    @Override
    public void onInitialize() {
        FabricLoader loader = FabricLoader.getInstance();
        boolean production = !loader.isDevelopmentEnvironment();
        Path configFile = loader.getConfigDir().resolve("skein").resolve("nrepl.properties");
        Properties file;
        try {
            file = loadProperties(configFile);
        } catch (IOException e) {
            // The unreadable file may be the one saying enabled=false — the
            // only safe reading is to keep the REPL off.
            LOGGER.error("[skein] cannot read {} — nREPL stays off until the file is fixed", configFile, e);
            return;
        }
        NreplConfig config = NreplConfig.resolve(production, file, System::getProperty);
        if (config instanceof NreplConfig.Invalid invalid) {
            LOGGER.error("[skein] nREPL not started: {}", invalid.error());
        } else if (config instanceof NreplConfig.Disabled disabled) {
            if (production) {
                LOGGER.debug("[skein] nREPL off: {}", disabled.reason());
            } else {
                LOGGER.info("[skein] dev nREPL off: {}", disabled.reason());
            }
        } else if (config instanceof NreplConfig.Enabled enabled) {
            start(enabled, production);
        }
    }

    private static Properties loadProperties(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }

    private static void start(NreplConfig.Enabled config, boolean production) {
        try {
            ClojureRuntime runtime = ClojureRuntime.get();
            runtime.requireNamespace("skein.repl");
            Var start = runtime.resolveVar("skein.repl", "start!");
            start.invoke(RT.map(
                    Keyword.intern("port"), config.port(),
                    Keyword.intern("bind"), config.bind(),
                    Keyword.intern("game-thread-eval?"), config.gameThreadEval()));
            if (production) {
                LOGGER.warn(
                        """

                    ************************************************************************
                    *  SKEIN PRODUCTION nREPL ENABLED — listening on {}:{}
                    *
                    *  Anyone who can connect to this port has FULL CONTROL of this JVM:
                    *  eval of arbitrary code, file system, process. The bind is
                    *  loopback-only; NEVER expose it to the network (no port forwarding,
                    *  no reverse proxy) — for remote access use an SSH tunnel:
                    *      ssh -L {}:127.0.0.1:{} user@host
                    *
                    *  Disable: remove enabled=true from config/skein/nrepl.properties
                    *  (or start with -Dskein.nrepl.disabled=true).
                    ************************************************************************""",
                        config.bind(),
                        config.port(),
                        config.port(),
                        config.port());
            } else {
                LOGGER.info(
                        "[skein] dev nREPL server listening on {}:{}{} — connect with CIDER/Calva"
                                + " (opt out with -Dskein.nrepl.disabled=true)",
                        config.bind(),
                        config.port(),
                        config.gameThreadEval() ? " (game-thread eval)" : "");
            }
        } catch (Throwable t) {
            LOGGER.error("[skein] failed to start the nREPL server — continuing without a REPL", t);
        }
    }
}
