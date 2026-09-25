package fyi.allme.allus.companydata;

/**
 * A required plugin input cannot be sent. {@link #getInput()} names the plugin's input key,
 * {@link #getSource()} the key it is wired to ({@code null} when unwired) and {@link #getReason()}
 * says why — checked in this order: {@link #UNWIRED}, {@link #UNANSWERED},
 * {@link #OTHER_PARTY_PRIVATE}, {@link #NOT_CONVERTIBLE}.
 */
public class PluginInputUnavailableException extends RuntimeException {
    /** The company wired no source to the input. */
    public static final String UNWIRED = "unwired";
    /** The input's source has no value yet. */
    public static final String UNANSWERED = "unanswered";
    /** The source is another party's private value, which is never sent to a plugin. */
    public static final String OTHER_PARTY_PRIVATE = "other_party_private";
    /** The source's value does not convert to the input's declared type. */
    public static final String NOT_CONVERTIBLE = "not_convertible";

    private final String input;
    private final String source;
    private final String reason;

    public PluginInputUnavailableException(String input, String source, String reason) {
        super("plugin input \"" + input + "\" is unavailable: " + describe(reason));
        this.input = input;
        this.source = source;
        this.reason = reason;
    }

    private static String describe(String reason) {
        switch (reason) {
            case UNWIRED: return "no source is wired to it";
            case UNANSWERED: return "its source has no value yet";
            case OTHER_PARTY_PRIVATE: return "its source is another party's private value";
            case NOT_CONVERTIBLE: return "its source's value does not convert to the input's type";
            default: return reason;
        }
    }

    /** The plugin's input key. */
    public String getInput() {
        return input;
    }

    /** The key the input is wired to, or {@code null} when it is unwired. */
    public String getSource() {
        return source;
    }

    /** Why the input cannot be sent. */
    public String getReason() {
        return reason;
    }
}
