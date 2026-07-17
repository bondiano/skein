package skein.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import groovy.json.JsonSlurper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MixinModJsonTest {

    private static final String CONFIG = "mymod.skein-mixins.json";

    private static Map<?, ?> parse(String json) {
        return (Map<?, ?>) new JsonSlurper().parseText(json);
    }

    @Test
    void createsTheMixinsArrayWhenAbsent() {
        String patched = MixinModJson.withConfig("{\"schemaVersion\": 1, \"id\": \"mymod\"}", CONFIG);
        Map<?, ?> parsed = parse(patched);
        assertEquals(List.of(CONFIG), parsed.get("mixins"));
        assertEquals("mymod", parsed.get("id"), "other keys survive the round trip");
    }

    @Test
    void appendsToAnExistingArray() {
        String patched =
                MixinModJson.withConfig("{\"id\": \"mymod\", \"mixins\": [\"handwritten.mixins.json\"]}", CONFIG);
        assertEquals(List.of("handwritten.mixins.json", CONFIG), parse(patched).get("mixins"));
    }

    @Test
    void leavesAnAlreadyRegisteredConfigAlone() {
        assertNull(MixinModJson.withConfig("{\"mixins\": [\"" + CONFIG + "\"]}", CONFIG));
    }

    @Test
    void keepsObjectFormEntries() {
        String patched = MixinModJson.withConfig(
                "{\"mixins\": [{\"config\": \"client.mixins.json\", \"environment\": \"client\"}]}", CONFIG);
        List<?> mixins = (List<?>) parse(patched).get("mixins");
        assertEquals(2, mixins.size());
        assertTrue(mixins.get(0) instanceof Map, "the object entry is preserved");
        assertEquals(CONFIG, mixins.get(1));
    }
}
