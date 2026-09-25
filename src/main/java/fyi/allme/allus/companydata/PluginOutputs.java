package fyi.allme.allus.companydata;

import java.util.Map;

/**
 * The outputs a plugin computed, by output key (a {@code null} value means the plugin had none).
 *
 * @param outputs output key → value of the output's declared type
 */
public record PluginOutputs(Map<String, Object> outputs) implements PluginOutputsResult {
}
