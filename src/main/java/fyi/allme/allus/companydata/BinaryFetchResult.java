package fyi.allme.allus.companydata;

import java.util.Locale;

/**
 * One response from a company-facing binary file endpoint, in the shape a
 * {@link BinaryHandle} needs.
 *
 * <p>The route has THREE 200 shapes and the company cannot predict which it will
 * get, because the answer depends on the person's own privacy setting and on the
 * TYPE of the field they answered with, neither of which the company chooses:
 *
 * <ul>
 *   <li><b>encrypted</b> — {@code application/json},
 *       {@code {"encrypted":true,"value":<wrapper>}}. The wrapper decrypts to the
 *       binary ENVELOPE string.</li>
 *   <li><b>envelope</b> — {@code application/json},
 *       {@code {"encrypted":false,"value":"<envelope>"}}. The plaintext envelope
 *       string itself, for a non-private source whose type stores more than one
 *       file or declares metadata entries. Nothing to decrypt.</li>
 *   <li><b>plaintext bytes</b> — the file's own {@code Content-Type} (e.g.
 *       {@code image/jpeg}, {@code application/pdf}) and the body IS the file
 *       bytes.</li>
 * </ul>
 *
 * <p>The bytes shape is told apart from the two JSON ones on the response's
 * {@code Content-Type} (see {@link #isPlaintextShape}), never guessed from the
 * body: a plaintext answer's first byte is whatever the file starts with, and a
 * PDF or a JPEG that happened to begin with a brace would be indistinguishable
 * from a wrapper by sniffing. Inside a JSON body it is {@code encrypted} that
 * decides; a JSON body that does not carry {@code encrypted: false} with a string
 * {@code value} is the wrapper arm, which is what the bare-wrapper routes
 * (a company's own contract copy, its run slot file) answer with.
 *
 * <p>{@link #contentSha256()} is the platform's {@code X-Allus-Content-Sha256} —
 * the sha256 of the SERVED ARTIFACT: the raw bytes on the bytes shape, the served
 * {@code value} string on either JSON shape — so a consumer can record what it
 * received and later prove its archived copy has not drifted.
 *
 * @param encrypted     whether the encrypted shape arrived
 * @param wrapper       the {@code {"_enc":1,…}} wrapper (encrypted shape), else null
 * @param bytes         the file bytes themselves (plaintext-bytes shape), else null
 * @param contentType   the response {@code Content-Type}, or null when absent
 * @param contentSha256 the {@code X-Allus-Content-Sha256} digest, or null when absent
 * @param envelope      the plaintext envelope string (envelope shape), else null
 */
public record BinaryFetchResult(
    boolean encrypted,
    Wrapper wrapper,
    byte[] bytes,
    String contentType,
    String contentSha256,
    String envelope
) {
    /** The digest header the platform stamps on every 200 of a binary file route. */
    public static final String DIGEST_HEADER = "X-Allus-Content-Sha256";

    /** The encrypted shape — a wrapper to decrypt, with the response's headers. */
    public static BinaryFetchResult encrypted(Wrapper wrapper, String contentType, String contentSha256) {
        return new BinaryFetchResult(true, wrapper, null, contentType, contentSha256, null);
    }

    /** The encrypted shape with no headers to carry (hand-wired fetches and tests). */
    public static BinaryFetchResult encrypted(Wrapper wrapper) {
        return encrypted(wrapper, null, null);
    }

    /** The plaintext-BYTES shape — the file bytes themselves, with the response's headers. */
    public static BinaryFetchResult plaintext(byte[] bytes, String contentType, String contentSha256) {
        return new BinaryFetchResult(false, null, bytes, contentType, contentSha256, null);
    }

    /** The ENVELOPE shape — the plaintext envelope string, with the response's headers. */
    public static BinaryFetchResult envelope(String envelopeJson, String contentType, String contentSha256) {
        return new BinaryFetchResult(false, null, null, contentType, contentSha256, envelopeJson);
    }

    /**
     * Whether a binary file response carries the file bytes themselves rather than the JSON wrapper
     * envelope — decided on {@code Content-Type} alone.
     *
     * <p>Plaintext is claimed ONLY on a Content-Type that positively says so. A missing or
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
