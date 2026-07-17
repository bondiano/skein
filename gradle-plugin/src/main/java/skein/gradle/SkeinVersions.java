package skein.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Versions baked into the plugin at its own build time from the version
 * catalog: applying the plugin is the one line that gives a mod the same
 * Clojure the adapter bundles — no version to copy around.
 */
final class SkeinVersions {

    private static final String RESOURCE = "/skein/gradle/versions.properties";
    private static final Properties PROPERTIES = load();

    private SkeinVersions() {}

    static String clojure() {
        return require("clojure");
    }

    static String nrepl() {
        return require("nrepl");
    }

    static String toolsDeps() {
        return require("tools-deps");
    }

    private static String require(String key) {
        String value = PROPERTIES.getProperty(key);
        if (value == null) {
            throw new IllegalStateException("Corrupt Skein plugin jar: no '" + key + "' in " + RESOURCE);
        }
        return value;
    }

    private static Properties load() {
        try (InputStream stream = SkeinVersions.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Corrupt Skein plugin jar: missing " + RESOURCE);
            }
            Properties properties = new Properties();
            properties.load(stream);
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        }
    }
}
