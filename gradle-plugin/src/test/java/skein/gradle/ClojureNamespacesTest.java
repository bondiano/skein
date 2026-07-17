package skein.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Namespace discovery, ns-naming lint and reflection-warning counting. */
class ClojureNamespacesTest {

    @TempDir
    Path dir;

    @Test
    void discoversNamespacesFromSourceTree() throws IOException {
        touch("my_mod/core.clj");
        touch("my_mod/util/http_client.cljc");
        touch("my_mod/data_readers.clj"); // reader config, not a namespace
        touch("my_mod/notes.txt");

        assertEquals(List.of("my-mod.core", "my-mod.util.http-client"), ClojureNamespaces.discover(dir.toFile()));
    }

    @Test
    void discoverOfMissingDirIsEmpty() {
        assertTrue(ClojureNamespaces.discover(dir.resolve("absent").toFile()).isEmpty());
    }

    @Test
    void nsLintAcceptsModIdRootWithUnderscoreDashNormalization() {
        assertTrue(ClojureNamespaces.nsLintViolations(
                        "my_mod", List.of("my-mod.core", "my-mod.util.http-client", "my-mod"))
                .isEmpty());
    }

    @Test
    void nsLintFlagsForeignRoots() {
        assertEquals(
                List.of("other.core", "single"),
                ClojureNamespaces.nsLintViolations("my_mod", List.of("my-mod.core", "other.core", "single")));
    }

    @Test
    void parsesModIdFromFabricModJson() throws IOException {
        Path json = dir.resolve("fabric.mod.json");
        Files.writeString(
                json,
                """
                {
                \t"schemaVersion": 1,
                \t"id": "my_mod",
                \t"version": "1.0.0"
                }
                """);
        assertEquals("my_mod", ClojureNamespaces.parseModId(json.toFile()).orElseThrow());
    }

    @Test
    void modIdOfMissingFileIsEmpty() {
        assertTrue(ClojureNamespaces.parseModId(dir.resolve("fabric.mod.json").toFile())
                .isEmpty());
    }

    @Test
    void countsReflectionWarningLines() {
        String output =
                """
                Compiling my-mod.core to /build/classes/clojure/main
                Reflection warning, my_mod/core.clj:7:3 - reference to field getServer can't be resolved.
                Reflection warning, my_mod/core.clj:9:5 - call to method execute can't be resolved (target class is unknown).
                Some other stderr noise mentioning Reflection warning, mid-line.
                """;
        assertEquals(2, ClojureNamespaces.countReflectionWarnings(output));
    }

    @Test
    void noWarningsInCleanOutput() {
        assertEquals(0, ClojureNamespaces.countReflectionWarnings("Compiling my-mod.core\n"));
    }

    private void touch(String relative) throws IOException {
        Path file = dir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, ";; fixture\n");
    }
}
