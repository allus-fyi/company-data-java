package fyi.allme.allus.companydata;

import java.util.List;
import java.util.Map;

/**
 * A run's answers as the company's service key opens them.
 *
 * <p>{@link #answers()} holds every answer the key opened, {@code {slug: plaintext}};
 * {@link #unreadable()} lists the slugs of the answers present on the run that it could not open
 * (sealed to a key the service has since replaced, or a wrong configured key), empty when every
 * answer opened. An unreadable slug is never in {@link #answers()}.
 */
public record FlowRunAnswers(Map<String, Object> answers, List<String> unreadable) {
}
