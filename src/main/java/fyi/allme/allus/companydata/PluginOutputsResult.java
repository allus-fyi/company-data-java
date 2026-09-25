package fyi.allme.allus.companydata;

/**
 * What {@code pluginOutputs} answers: {@link PluginOutputs}, or {@link PluginPicksInvalid} when
 * the picks no longer fit the inputs or each other.
 */
public sealed interface PluginOutputsResult permits PluginOutputs, PluginPicksInvalid {
}
