package skein.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import clojure.java.api.Clojure;
import clojure.lang.Keyword;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Production smoke test: the built adapter + example-mod jars on the real
 * Fabric server launcher (fabric-server-launch, real Knot, JiJ extraction —
 * none of the Loom dev classpath). Two boots of a dedicated server:
 *
 * <ol>
 *   <li>with the opt-in config file — the production nREPL comes up on a
 *       loopback port, a real nREPL client connects and exercises interop
 *       with live server classes, game-thread dispatch and re-def (the
 *       checks live in {@code skein_prod_smoke/client.clj});</li>
 *   <li>without the config file — the port stays closed and the log stays
 *       free of nREPL mentions: production REPL is strictly opt-in.</li>
 * </ol>
 *
 * <p>Needs network on first run (the launcher downloads the MC server).
 * Driven by the {@code prodReplSmokeTest} Gradle task, which passes the jar
 * locations and the scratch run dir as system properties.
 */
class ProdReplSmokeTest {

    private static final Duration FIRST_BOOT_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration STOP_TIMEOUT = Duration.ofMinutes(2);

    private static Path runDir;
    private static Path launcherJar;
    private static Path configFile;

    @BeforeAll
    static void prepareRunDir() throws IOException {
        launcherJar = Path.of(System.getProperty("skein.smoke.launcherJar"));
        runDir = Path.of(System.getProperty("skein.smoke.runDir"));
        Path mods = runDir.resolve("mods");
        if (Files.exists(mods)) {
            try (var jars = Files.list(mods)) {
                for (Path stale : jars.toList()) {
                    Files.delete(stale);
                }
            }
        }
        Files.createDirectories(mods);
        for (String jar : System.getProperty("skein.smoke.modJars").split(java.io.File.pathSeparator)) {
            Path source = Path.of(jar);
            Files.copy(source, mods.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(runDir.resolve("eula.txt"), "eula=true\n");
        // max-players=13 is the marker the REPL checks read back through
        // (.getMaxPlayers (.getPlayerList server)) — a full config-file ->
        // live-server-object -> eval round trip.
        Files.writeString(
                runDir.resolve("server.properties"),
                """
                server-port=%d
                online-mode=false
                max-players=13
                level-type=minecraft:flat
                generate-structures=false
                sync-chunk-writes=false
                motd=skein prod REPL smoke
                """
                        .formatted(freePort()));
        configFile = runDir.resolve("config").resolve("skein").resolve("nrepl.properties");
    }

    @Test
    void optInReplServesLiveServerInterop() throws Exception {
        int nreplPort = freePort();
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, "enabled=true\nport=" + nreplPort + "\n");
        Path log = runDir.resolve("console-opt-in.log");

        Process server = startServer(log);
        try {
            waitFor(log, "Done", server);
            waitForPort(nreplPort, server, log);

            Map<?, ?> r = runClientChecks(nreplPort);
            assertEquals(List.of("3"), r.get(kw("arith")), "plain eval");
            assertEquals(List.of("\"SKEIN\""), r.get(kw("interop")), "java interop");
            assertEquals(List.of("13"), r.get(kw("max-players")), "interop with the live MinecraftServer");
            assertEquals(List.of("\"Server thread\""), r.get(kw("server-thread")), "on-game dispatch");
            assertEquals(List.of(":v2"), r.get(kw("redef")), "hotfix re-def in a live prod session");
            assertTrue(
                    String.join("", (List<String>) r.get(kw("add-lib-blocked"))).contains("dev-only"),
                    "add-lib! must refuse in production");
        } finally {
            stopServer(server);
        }
        assertTrue(
                Files.readString(log).contains("SKEIN PRODUCTION nREPL ENABLED"),
                "activation must shout a warning into the log");
    }

    @Test
    void withoutOptInThePortStaysClosed() throws Exception {
        int probePort = freePort();
        Files.deleteIfExists(configFile);
        Path log = runDir.resolve("console-no-opt-in.log");

        Process server = startServer(log);
        try {
            waitFor(log, "Done", server);
            // Only the freshly-picked free port is probed: 7888 may be held
            // by an unrelated host process (an editor's own nREPL).
            assertFalse(canConnect(probePort), "no config, no listener");
        } finally {
            stopServer(server);
        }
        // The mod list still mentions the JiJ'd nrepl jar, so probe for the
        // start markers, not the substring.
        String console = Files.readString(log);
        assertFalse(console.contains("SKEIN PRODUCTION nREPL ENABLED"), "no banner without opt-in");
        assertFalse(console.contains("nREPL server listening"), "no server start without opt-in");
    }

    private static Process startServer(Path log) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder(java, "-Xmx2G", "-jar", launcherJar.toString(), "nogui");
        builder.directory(runDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        return builder.start();
    }

    private static void stopServer(Process server) throws Exception {
        if (!server.isAlive()) {
            return;
        }
        OutputStream stdin = server.getOutputStream();
        stdin.write("stop\n".getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        if (!server.waitFor(STOP_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            server.destroyForcibly();
            fail("server did not stop within " + STOP_TIMEOUT);
        }
    }

    /** Waits until the console log contains {@code marker} (server boot progress). */
    private static void waitFor(Path log, String marker, Process server) throws Exception {
        Instant deadline = Instant.now().plus(FIRST_BOOT_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (Files.exists(log) && Files.readString(log).contains(marker)) {
                return;
            }
            if (!server.isAlive()) {
                fail("server exited with code " + server.exitValue() + " before '" + marker + "'; log:\n" + tail(log));
            }
            Thread.sleep(500);
        }
        fail("no '" + marker + "' in the server log within " + FIRST_BOOT_TIMEOUT + "; log:\n" + tail(log));
    }

    private static void waitForPort(int port, Process server, Path log) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline) && server.isAlive()) {
            if (canConnect(port)) {
                return;
            }
            Thread.sleep(250);
        }
        fail("nREPL port " + port + " never opened; log:\n" + tail(log));
    }

    private static boolean canConnect(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (ConnectException e) {
            return false;
        }
    }

    private static Map<?, ?> runClientChecks(int port) {
        Clojure.var("clojure.core", "require").invoke(Clojure.read("skein-prod-smoke.client"));
        return (Map<?, ?>) Clojure.var("skein-prod-smoke.client", "run-checks").invoke((long) port);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String tail(Path log) throws IOException {
        if (!Files.exists(log)) {
            return "<no log file>";
        }
        List<String> lines = Files.readAllLines(log);
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 60), lines.size()));
    }

    private static Keyword kw(String name) {
        return Keyword.intern(name);
    }
}
