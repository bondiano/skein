package skein.example;

import clojure.lang.RT;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;

/**
 * The L2 domain layer against a live dedicated server, headless. Unlike
 * {@link ClojureTests} (which only bootstraps vanilla content and runs the mod
 * entrypoint), this boots a real {@link MinecraftServer} in-process — a flat
 * world with structures off — so the world/entity/inventory effects run against
 * an actual {@code ServerLevel} on the real server thread.
 *
 * <p>It runs in its own forked JVM (the {@code l2ServerTest} Gradle task, side =
 * server, working dir = a scratch run directory) so the full server boot never
 * collides with the lightweight {@link ClojureTests} suite. The assertions live
 * in the {@code skein-example-test.l2-test} Clojure namespace; this class only
 * boots the server, hands it over, and stops it.
 */
class L2ServerTests {

    static MinecraftServer server;

    @BeforeAll
    static void bootServer() throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        Files.writeString(cwd.resolve("eula.txt"), "eula=true\n");
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Files.writeString(
                cwd.resolve("server.properties"),
                "server-port=" + port + "\n"
                        + "online-mode=false\n"
                        + "level-type=minecraft:flat\n"
                        + "generate-structures=false\n"
                        + "sync-chunk-writes=false\n"
                        + "max-players=7\n"
                        + "level-name=l2world\n");

        // Boots the dedicated server on its own "Server thread" and returns.
        net.minecraft.server.Main.main(new String[] {"--nogui"});

        FabricLoader loader = FabricLoader.getInstance();
        long deadline = System.currentTimeMillis() + 180_000;
        while (System.currentTimeMillis() < deadline) {
            Object instance = loader.getGameInstance();
            if (instance instanceof MinecraftServer running && running.isReady()) {
                server = running;
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("dedicated server did not reach ready within 180s");
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.halt(false);
        }
    }

    @TestFactory
    @SuppressWarnings("unchecked")
    Collection<DynamicNode> l2Suites() {
        skein.runtime.ClojureRuntime.get().requireNamespace("skein-example-test.harness");
        skein.runtime.ClojureRuntime.get().requireNamespace("skein-example-test.l2-test");
        skein.runtime.ClojureRuntime.get().requireNamespace("skein-example-test.state-test");
        RT.var("skein-example-test.l2-test", "set-server!").invoke(server);
        RT.var("skein-example-test.state-test", "set-server!").invoke(server);
        return (Collection<DynamicNode>) RT.var("skein-example-test.harness", "suites")
                .invoke(List.of("skein-example-test.l2-test", "skein-example-test.state-test"));
    }
}
