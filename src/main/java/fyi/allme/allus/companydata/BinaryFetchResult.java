package fyi.allme.allus.companydata;

import java.util.Locale;

/**
 * One response from a company-facing binary file endpoint, in the shape a
 * {@link BinaryHandle} needs.
 *
 * <p>#590 — the route has TWO 200 shapes and the company cannot predict which it
 * will get, because the answer depends on whether the person's source field is
 * private, which is theirs to change:
 *
 * <ul>
 *   <li><b>encrypted</b> — {@code application/json},
 *       {@code {"encrypted":true,"value":<wrapper>}}. The wrapper decrypts to the
 *       binary ENVELOPE string, from which the file bytes are extracted.</li>
 *   <li><b>plaintext</b> — the file's own {@code Content-Type} (e.g.
 *       {@code image/jpeg}, {@code application/pdf}) and the body IS the file
 *       bytes. Nothing to decrypt.</li>
 * </ul>
 *
 * <p>The distinction is made on the response's {@code Content-Type} (see
 * {@link #isPlaintextShape}), never guessed from the body: a plaintext answer's
 * first byte is whatever the file starts with, and a PDF or a JPEG that happened
 * to begin with a brace would be indistinguishable from a wrapper by sniffing.
 *
 * <p>{@link #contentSha256()} is the platform's {@code X-Allus-Content-Sha256} —
 * the sha256 of exactly these bytes, present on both shapes — so a consumer can
 * record what it received and later prove its archived copy has not drifted.
 *
 * @param encrypted     whether the encrypted shape arrived
 * @param wrapper       the {@code {"_enc":1,…}} wrapper (encrypted shape), else null
 * @param bytes         the file bytes themselves (plaintext shape), else null
 * @param contentType   the response {@code Content-Type}, or null when absent
 * @param contentSha256 the {@code X-Allus-Content-Sha256} digest, or null when absent
 */
public record BinaryFetchResult(
    boolean encrypted,
    Wrapper wrapper,
    byte[] bytes,
    String contentType,
    String contentSha256
) {
    /** The digest header the platform stamps on every 200 of a binary file route. */
    public static final String DIGEST_HEADER = "X-Allus-Content-Sha256";

    /** The encrypted shape — a wrapper to decrypt, with the response's headers. */
    public static BinaryFetchResult encrypted(Wrapper wrapper, String contentType, String contentSha256) {
        return new BinaryFetchResult(true, wrapper, null, contentType, contentSha256);
    }

    /** The encrypted shape with no headers to carry (hand-wired fetches and tests). */
    public static BinaryFetchResult encrypted(Wrapper wrapper) {
        return encrypted(wrapper, null, null);
    }

    /** The plaintext shape — the file bytes themselves, with the response's headers. */
    public static BinaryFetchResult plaintext(byte[] bytes, String contentType, String contentSha256) {
        return new BinaryFetchResult(false, null, bytes, contentType, contentSha256);
    }

    /**
     * Whether a binary file response carries the file bytes themselves rather than the JSON wrapper
     * envelope — decided on {@code Content-Type} alone.
     *
     * <p>#590: plaintext is claimed ONLY on a Content-Type that positively says so. A missing or
     * empty header falls through to the JSON path — the historical shape — because the two failure
     * modes are not symmetrical: mistaking a wrapper for file bytes writes the ciphertext envelope
     * to disk as if it were the document and nothing complains, while mistaking bytes for a wrapper
     * fails loudly at the parse. Guess towards the loud one.
     */
    public static boolean isPlaintextShape(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            return false;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        return !ct.contains("json") && !ct.contains("xml");
    }
}
