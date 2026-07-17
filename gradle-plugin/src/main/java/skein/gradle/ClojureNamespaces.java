package skein.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Pure helpers behind the Skein plugin: namespace discovery for AOT,
 * the ns-naming lint and reflection-warning counting. Kept side-effect
 * free so they are unit-testable without Gradle.
 */
final class ClojureNamespaces {

    /** Not namespaces: reader configuration files loaded by name. */
    private static final Set<String> NON_NAMESPACE_FILES = Set.of("data_readers.clj", "data_readers.cljc");

    private static final Pattern MOD_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REFLECTION_WARNING = Pattern.compile("^Reflection warning, ", Pattern.MULTILINE);

    private ClojureNamespaces() {}

    /**
     * Namespace names for every {@code .clj}/{@code .cljc} file under
     * {@code srcDir}, derived from the file path (Clojure's classpath
     * convention: {@code /} → {@code .}, {@code _} → {@code -}), sorted.
     */
    static List<String> discover(File srcDir) {
        if (!srcDir.isDirectory()) {
            return List.of();
        }
        Path root = srcDir.toPath();
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return (name.endsWith(".clj") || name.endsWith(".cljc")) && !NON_NAMESPACE_FILES.contains(name);
                    })
                    .map(path -> toNamespace(root.relativize(path)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to scan Clojure sources in " + srcDir, e);
        }
    }

    static String toNamespace(Path relativeSource) {
        String path = relativeSource.toString().replace(File.separatorChar, '/');
        String withoutExtension = path.substring(0, path.lastIndexOf('.'));
        return withoutExtension.replace('/', '.').replace('_', '-');
    }

    /**
     * Namespaces whose root segment is not the mod id (convention
     * {@code <modid>.*}). Mod ids use {@code _} where ns names use {@code -};
     * both sides are compared normalized.
     */
    static List<String> nsLintViolations(String modId, List<String> namespaces) {
        String expectedRoot = modId.replace('_', '-');
        return namespaces.stream()
                .filter(ns -> {
                    int dot = ns.indexOf('.');
                    String root = dot < 0 ? ns : ns.substring(0, dot);
                    return !root.equals(expectedRoot);
                })
                .toList();
    }

    /** The {@code "id"} of a {@code fabric.mod.json}, if the file parses. */
    static Optional<String> parseModId(File fabricModJson) {
        if (!fabricModJson.isFile()) {
            return Optional.empty();
        }
        try {
            Matcher matcher = MOD_ID.matcher(Files.readString(fabricModJson.toPath(), StandardCharsets.UTF_8));
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + fabricModJson, e);
        }
    }

    /** Number of {@code *warn-on-reflection*} warnings in compiler output. */
    static long countReflectionWarnings(String compilerOutput) {
        return REFLECTION_WARNING.matcher(compilerOutput).results().count();
    }
}
