package fyi.allme.allus.companydata;

import java.util.List;

/**
 * A plugin's option list for one search_select block.
 *
 * @param options the options, at most 50
 * @param more    the list was cut; a longer query narrows it
 */
public record PluginOptionsResult(List<Option> options, boolean more) {

    /** One option a plugin serves. */
    public record Option(String id, String label) {
    }
}
