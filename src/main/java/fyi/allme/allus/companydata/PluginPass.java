package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A short-lived pass for the plugin forwarder: the pass itself, the forwarder's address, the
 * plugins it unlocks and the plugin description ({@code plugin_spec}) of every plugin element it
 * covers, keyed by the element's slug.
 *
 * @param pass         the pass (a signed token the forwarder verifies)
 * @param forwarderUrl the forwarder's base address, without a trailing slash
 * @param plugins      the plugins the pass unlocks
 * @param specs        slug → plugin_spec
 * @param raw          the API response
 */
public record PluginPass(
    String pass,
    String forwarderUrl,
    List<Plugin> plugins,
    Map<String, Map<String, Object>> specs,
    Map<String, Object> raw
) {
    /**
     * One plugin the pass unlocks. {@code publicKey} is {@code null} when the plugin's description
     * is missing or failed; such a plugin is not responding.
     */
    public record Plugin(String id, String publicKey) {
    }

    @SuppressWarnings("unchecked")
    static PluginPass fromApi(Object body) {
        Map<String, Object> obj = body instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<Plugin> plugins = new ArrayList<>();
        if (obj.get("plugins") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> pm) {
                    plugins.add(new Plugin(Parse.str(pm.get("id")), Parse.str(pm.get("public_key"))));
                }
            }
        }
        Map<String, Map<String, Object>> specs = new LinkedHashMap<>();
        if (obj.get("specs") instanceof Map<?, ?> sm) {
            for (Map.Entry<?, ?> e : sm.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> spec) {
                    specs.put(String.valueOf(e.getKey()), (Map<String, Object>) spec);
                }
            }
        }
        String url = Parse.str(obj.get("forwarder_url"));
        while (url != null && url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return new PluginPass(Parse.str(obj.get("pass")), url, plugins, specs, obj);
    }

    String publicKeyFor(String pluginId) {
        for (Plugin p : plugins) {
            if (p.id() != null && p.id().equals(pluginId)) {
                return p.publicKey();
            }
        }
        return null;
    }
}
