package skein.gradle;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The pure part of the fabric.mod.json mixin-config registration: adds
 * the generated Skein config name to the {@code "mixins"} array. Only
 * ever applied to build output (the processed resource copy), never to
 * the author's source file.
 */
final class MixinModJson {

    private MixinModJson() {}

    /**
     * The patched JSON with {@code configName} appended to
     * {@code "mixins"} (creating the array when absent), or {@code null}
     * when the config is already registered and the file should stay
     * untouched.
     */
    @SuppressWarnings("unchecked")
    static String withConfig(String fabricModJson, String configName) {
        Map<String, Object> parsed = (Map<String, Object>) new JsonSlurper().parseText(fabricModJson);
        Object mixins = parsed.get("mixins");
        List<Object> entries = mixins instanceof List<?> existing ? new ArrayList<>(existing) : new ArrayList<>();
        // Entries may be strings or {"config": ..} maps; the generated name
        // is always registered as a plain string.
        if (entries.contains(configName)) {
            return null;
        }
        entries.add(configName);
        parsed.put("mixins", entries);
        return JsonOutput.prettyPrint(JsonOutput.toJson(parsed));
    }
}
