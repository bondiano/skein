package skein.adapter;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.function.UnaryOperator;

/**
 * Resolved nREPL startup decision: {@link Enabled} with the settings to use,
 * {@link Disabled} for the normal "no REPL" state, or {@link Invalid} when
 * the configuration is broken — the REPL stays off and the error is logged.
 *
 * <p>Sources, later ones losing to earlier ones (system property beats the
 * config file, the file beats the default):
 *
 * <ol>
 *   <li>JVM properties {@code skein.nrepl.enabled}, {@code skein.nrepl.port},
 *       {@code skein.nrepl.bind}, {@code skein.nrepl.game-thread-eval}, plus
 *       the kill switch {@code skein.nrepl.disabled} that always wins;</li>
 *   <li>{@code config/skein/nrepl.properties} with the keys {@code enabled},
 *       {@code port}, {@code bind}, {@code game-thread-eval};</li>
 *   <li>defaults: port 7888, bind 127.0.0.1, game-thread-eval off; enabled
 *       in dev, disabled in production.</li>
 * </ol>
 *
 * <p>Production hardening: the REPL is strictly opt-in (an explicit
 * {@code enabled=true} somewhere), and the bind address must be loopback —
 * an nREPL session is full control of the JVM, so remote access must go
 * through the operator's own channel (an SSH tunnel), never a raw open port.
 */
public sealed interface NreplConfig {

    /** Start the server with these settings. */
    record Enabled(int port, String bind, boolean gameThreadEval) implements NreplConfig {}

    /** Do not start; {@code reason} explains which switch said so. */
    record Disabled(String reason) implements NreplConfig {}

    /** Configuration error: do not start, log {@code error} loudly. */
    record Invalid(String error) implements NreplConfig {}

    /**
     * Resolves the startup decision from the config file contents and system
     * properties ({@code sysProp} is {@code System::getProperty} in
     * production code, injectable in tests).
     */
    static NreplConfig resolve(boolean production, Properties file, UnaryOperator<String> sysProp) {
        try {
            Source source = new Source(file, sysProp);
            if (source.bool("skein.nrepl.disabled", "disabled", false)) {
                return new Disabled("disabled via -Dskein.nrepl.disabled=true");
            }
            if (!source.bool("skein.nrepl.enabled", "enabled", !production)) {
                return new Disabled(
                        production
                                ? "production REPL is strictly opt-in — enable with enabled=true in"
                                        + " config/skein/nrepl.properties or -Dskein.nrepl.enabled=true"
                                : "disabled via enabled=false");
            }
            int port = source.port();
            String bind = source.string("skein.nrepl.bind", "bind", "127.0.0.1");
            if (production && !isLoopback(bind)) {
                return new Invalid("production nREPL must bind a loopback address, got '" + bind
                        + "' — an nREPL session is full control of the JVM, so the port must never be"
                        + " reachable from the network. Remove the bind override (default 127.0.0.1)"
                        + " and use an SSH tunnel for remote access.");
            }
            return new Enabled(port, bind, source.bool("skein.nrepl.game-thread-eval", "game-thread-eval", false));
        } catch (IllegalArgumentException e) {
            return new Invalid(e.getMessage());
        }
    }

    private static boolean isLoopback(String bind) {
        try {
            return InetAddress.getByName(bind).isLoopbackAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("bind address '" + bind + "' does not resolve: " + e.getMessage());
        }
    }

    /** One config value: system property first, then the file, then the default. */
    final class Source {
        private final Properties file;
        private final UnaryOperator<String> sysProp;

        private Source(Properties file, UnaryOperator<String> sysProp) {
            this.file = file;
            this.sysProp = sysProp;
        }

        private String raw(String propertyKey, String fileKey) {
            String fromProperty = sysProp.apply(propertyKey);
            if (fromProperty != null) {
                return fromProperty;
            }
            return file == null ? null : file.getProperty(fileKey);
        }

        private String string(String propertyKey, String fileKey, String defaultValue) {
            String value = raw(propertyKey, fileKey);
            return value == null || value.isBlank() ? defaultValue : value.trim();
        }

        private boolean bool(String propertyKey, String fileKey, boolean defaultValue) {
            String value = raw(propertyKey, fileKey);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            String normalized = value.trim();
            if ("true".equalsIgnoreCase(normalized)) {
                return true;
            }
            if ("false".equalsIgnoreCase(normalized)) {
                return false;
            }
            throw new IllegalArgumentException(fileKey + " must be true or false, got '" + value + "' (set via "
                    + propertyKey + " or the " + fileKey + " key in config/skein/nrepl.properties)");
        }

        private int port() {
            String value = raw("skein.nrepl.port", "port");
            if (value == null || value.isBlank()) {
                return 7888;
            }
            try {
                int port = Integer.parseInt(value.trim());
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("port must be between 1 and 65535, got " + port);
                }
                return port;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("port must be an integer between 1 and 65535, got '" + value + "'");
            }
        }
    }
}
