package skein.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Properties;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class NreplConfigTest {

    private static final boolean DEV = false;
    private static final boolean PRODUCTION = true;

    private static final UnaryOperator<String> NO_PROPS = key -> null;

    private static Properties file(String... keyValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            properties.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return properties;
    }

    private static UnaryOperator<String> props(String... keyValues) {
        Properties properties = file(keyValues);
        return properties::getProperty;
    }

    @Test
    void devDefaultsToEnabledOnLocalhost() {
        NreplConfig config = NreplConfig.resolve(DEV, null, NO_PROPS);
        assertEquals(new NreplConfig.Enabled(7888, "127.0.0.1", false), config);
    }

    @Test
    void productionDefaultsToDisabled() {
        NreplConfig config = NreplConfig.resolve(PRODUCTION, null, NO_PROPS);
        assertInstanceOf(NreplConfig.Disabled.class, config);
        assertTrue(((NreplConfig.Disabled) config).reason().contains("opt-in"));
    }

    @Test
    void productionOptInViaConfigFile() {
        NreplConfig config = NreplConfig.resolve(PRODUCTION, file("enabled", "true", "port", "7900"), NO_PROPS);
        assertEquals(new NreplConfig.Enabled(7900, "127.0.0.1", false), config);
    }

    @Test
    void productionOptInViaSystemProperty() {
        NreplConfig config = NreplConfig.resolve(PRODUCTION, null, props("skein.nrepl.enabled", "true"));
        assertEquals(new NreplConfig.Enabled(7888, "127.0.0.1", false), config);
    }

    @Test
    void systemPropertyBeatsConfigFile() {
        NreplConfig config = NreplConfig.resolve(
                PRODUCTION, file("enabled", "true", "port", "7900"), props("skein.nrepl.port", "7999"));
        assertEquals(new NreplConfig.Enabled(7999, "127.0.0.1", false), config);
    }

    @Test
    void disabledKillSwitchAlwaysWins() {
        NreplConfig config = NreplConfig.resolve(
                PRODUCTION,
                file("enabled", "true"),
                props("skein.nrepl.enabled", "true", "skein.nrepl.disabled", "true"));
        assertInstanceOf(NreplConfig.Disabled.class, config);
    }

    @Test
    void devOptOutViaConfigFile() {
        NreplConfig config = NreplConfig.resolve(DEV, file("enabled", "false"), NO_PROPS);
        assertInstanceOf(NreplConfig.Disabled.class, config);
    }

    @Test
    void productionRefusesNonLoopbackBind() {
        NreplConfig config = NreplConfig.resolve(PRODUCTION, file("enabled", "true", "bind", "0.0.0.0"), NO_PROPS);
        assertInstanceOf(NreplConfig.Invalid.class, config);
        String error = ((NreplConfig.Invalid) config).error();
        assertTrue(error.contains("loopback"), error);
        assertTrue(error.contains("SSH tunnel"), error);
    }

    @Test
    void productionAcceptsLoopbackSpellings() {
        for (String bind : new String[] {"127.0.0.1", "localhost", "::1"}) {
            NreplConfig config = NreplConfig.resolve(PRODUCTION, file("enabled", "true", "bind", bind), NO_PROPS);
            assertInstanceOf(NreplConfig.Enabled.class, config, bind);
        }
    }

    @Test
    void devAllowsNonLoopbackBind() {
        NreplConfig config = NreplConfig.resolve(DEV, file("bind", "0.0.0.0"), NO_PROPS);
        assertEquals(new NreplConfig.Enabled(7888, "0.0.0.0", false), config);
    }

    @Test
    void gameThreadEvalOptIn() {
        NreplConfig config = NreplConfig.resolve(DEV, file("game-thread-eval", "true"), NO_PROPS);
        assertEquals(new NreplConfig.Enabled(7888, "127.0.0.1", true), config);
    }

    @Test
    void invalidValuesFailFastWithTheOffendingKey() {
        Map<Properties, String> cases = Map.of(
                file("enabled", "yes"), "enabled",
                file("enabled", "true", "port", "not-a-port"), "port",
                file("enabled", "true", "port", "70000"), "port");
        cases.forEach((broken, key) -> {
            NreplConfig config = NreplConfig.resolve(PRODUCTION, broken, NO_PROPS);
            assertInstanceOf(NreplConfig.Invalid.class, config);
            assertTrue(((NreplConfig.Invalid) config).error().contains(key));
        });
    }

    @Test
    void blankValuesFallBackToDefaults() {
        NreplConfig config = NreplConfig.resolve(DEV, file("port", "", "bind", "  "), NO_PROPS);
        assertEquals(new NreplConfig.Enabled(7888, "127.0.0.1", false), config);
    }
}
