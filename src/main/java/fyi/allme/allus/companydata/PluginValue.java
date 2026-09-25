package fyi.allme.allus.companydata;

import com.fasterxml.jackson.core.JsonProcessingException;
import fyi.allme.allus.companydata.internal.Json;
import fyi.allme.allus.companydata.internal.Parse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A plugin answer: the plugin's name, the field type answered, the blocks in declared order and
 * the outputs. It is what a plugin field stores — a request row's company-data value, a flow
 * plugin element's answer, a sign-in plugin claim's value — as self-describing JSON:
 *
 * <pre>
 * {"plugin":"Flex","type":"cao",
 *  "blocks":[{"key":"cao","kind":"search_select","label":"CAO","id":"hrc","value":"Horeca Fictief"}],
 *  "outputs":[{"key":"min_wage","type":"number","label":"Minimum wage","value":9.5}]}
 * </pre>
 *
 * <p>An answer with no outputs array is unfinished and is not a {@code PluginValue}.
 *
 * @param plugin  the plugin's name
 * @param type    the plugin field type answered
 * @param blocks  the answered blocks, in declared order
 * @param outputs the outputs the plugin computed
 * @param raw     the parsed JSON object
 */
public record PluginValue(
    String plugin,
    String type,
    List<Block> blocks,
    List<Output> outputs,
    Map<String, Object> raw
) {

    /** The reserved field-type key of a plugin row; never a registry row. */
    public static final String TYPE_KEY = "plugin";

    /**
     * One answered block: a search_select pick carries its {@code id} and its option label as
     * {@code value}; a text, number or date block its typed {@code value} and a null {@code id}.
     */
    public record Block(String key, String kind, String label, String id, Object value) {
    }

    /**
     * One output, of its declared {@code type} (text, number, date or boolean); {@code value} is
     * null when the plugin had no value for it.
     */
    public record Output(String key, String type, String label, Object value) {
    }

    /**
     * Parse the plaintext of a plugin answer into a {@code PluginValue}.
     *
     * @throws ValidationException (field type {@code plugin}) when the plaintext is not a JSON
     *                             object with an {@code outputs} array; an unfinished answer has none
     */
    @SuppressWarnings("unchecked")
    public static PluginValue parse(String plaintext) {
        Object parsed;
        try {
            parsed = plaintext == null ? null : Json.parse(plaintext);
        } catch (JsonProcessingException exc) {
            throw new ValidationException(null, TYPE_KEY);
        }
        if (!(parsed instanceof Map<?, ?> m) || !(m.get("outputs") instanceof List<?>)) {
            throw new ValidationException(null, TYPE_KEY);
        }
        Map<String, Object> obj = (Map<String, Object>) m;
        List<Block> blocks = new ArrayList<>();
        if (obj.get("blocks") instanceof List<?> list) {
            for (Object b : list) {
                if (b instanceof Map<?, ?> bm) {
                    Object id = bm.get("id");
                    blocks.add(new Block(
                        Parse.str(bm.get("key")),
                        Parse.str(bm.get("kind")),
                        Parse.str(bm.get("label")),
                        id == null ? null : FlowCondition.str(id),
                        bm.get("value")));
                }
            }
        }
        List<Output> outputs = new ArrayList<>();
        if (obj.get("outputs") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> om) {
                    outputs.add(new Output(
                        Parse.str(om.get("key")),
                        Parse.str(om.get("type")),
                        Parse.str(om.get("label")),
                        om.get("value")));
                }
            }
        }
        return new PluginValue(
            Parse.str(obj.get("plugin")), Parse.str(obj.get("type")), blocks, outputs, obj);
    }
}
