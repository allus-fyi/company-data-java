package fyi.allme.allus.companydata;

import java.util.List;

/**
 * What every surface renders for a stored plugin answer: the blocks, then the outputs, in stored
 * order. Built by {@link FlowCondition#pluginAnswerView(String)}.
 *
 * @param blocks  each answered block's label and value (the option label for a search_select pick)
 * @param outputs each output's label, declared type and value
 */
public record PluginView(List<Block> blocks, List<Output> outputs) {

    /** One block of a stored plugin answer as shown to a reader. */
    public record Block(String label, Object value) {
    }

    /** One output of a stored plugin answer as shown to a reader. */
    public record Output(String label, String type, Object value) {
    }
}
