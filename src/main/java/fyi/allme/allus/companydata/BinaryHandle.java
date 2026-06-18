package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Lazy handle for a binary (photo/document) value.
 *
 * <p>A binary answer is stored server-side as a file, exposed in the hardened API
 * as a slot-keyed {@code value_url} (never the source field). On {@link #bytes()}
 * / {@link #save(Path)} the handle GETs that URL, receives the {@code {"_enc":1,...}}
 * wrapper, runs the same decrypt as text → a JSON envelope STRING (photo:
 * {@code {"full":"data:...","thumb":...}}; document: {@code {"file":"data:...",...}})
 * — NOT raw bytes — then parses the envelope and base64-decodes the primary data-URI
 * payload ({@code full} for photos, {@code file} for documents) into the file bytes.
 *
 * <p>The fetch + decrypt are supplied by the client as plain callables (config-only
 * key handling — no key is ever passed to this handle):
 * <ul>
 *   <li>{@code valueUrl} + {@code fetch} — {@code fetch.apply(valueUrl)} returns the
 *       encrypted wrapper (the client passes a callback that does the GET + unwraps
 *       the API's {@code {"encrypted":true,"value":<wrapper>}} envelope to the inner
 *       wrapper).</li>
 *   <li>{@code decrypt} — {@code decrypt.apply(wrapper)} returns the decrypted
 *       envelope string (a closure over the loaded service private key).</li>
 * </ul>
 *
 * <p>For the shared crypto test vector the decrypted envelope is already in hand, so
 * a handle can also be built directly via {@link #fromEnvelope(String)}.
 */
public final class BinaryHandle {
    /** Envelope keys holding the primary binary data URI, in priority order. */
    private static final List<String> DATA_URI_KEYS = List.of("full", "file");

    private String envelopeJson;            // cached once resolved
    private final String valueUrl;
    private final Function<String, Wrapper> fetch;
    private final Function<Wrapper, String> decrypt;

    private BinaryHandle(String envelopeJson, String valueUrl,
                         Function<String, Wrapper> fetch, Function<Wrapper, String> decrypt) {
        this.envelopeJson = envelopeJson;
        this.valueUrl = valueUrl;
        this.fetch = fetch;
        this.decrypt = decrypt;
    }

    /** A handle whose decrypted envelope is already in hand (test vector / inline). */
    public static BinaryHandle fromEnvelope(String envelopeJson) {
        return new BinaryHandle(envelopeJson, null, null, null);
    }

    /** A lazy handle that fetches+decrypts on first {@link #bytes()} / {@link #save(Path)}. */
    public static BinaryHandle lazy(String valueUrl, Function<String, Wrapper> fetch,
                                    Function<Wrapper, String> decrypt) {
        return new BinaryHandle(null, valueUrl, fetch, decrypt);
    }

    /** An empty handle (binary type but no value, e.g. unanswered). */
    public static BinaryHandle empty() {
        return new BinaryHandle(null, null, null, null);
    }

    /** The slot-keyed file URL this handle fetches from (opaque to callers; may be {@code null}). */
    public String valueUrl() {
        return valueUrl;
    }

    private String resolveEnvelope() {
        if (envelopeJson != null) {
            return envelopeJson;
        }
        if (fetch == null || decrypt == null || valueUrl == null) {
            throw new DecryptException(
                "BinaryHandle has no envelope and no fetch/decrypt wiring "
                    + "(build it with fromEnvelope, or lazy(valueUrl, fetch, decrypt))");
        }
        Wrapper wrapper = fetch.apply(valueUrl);
        String env = decrypt.apply(wrapper);
        this.envelopeJson = env; // cache so repeated calls don't re-fetch
        return env;
    }

    /**
     * Turn a decrypted binary envelope STRING into the primary file bytes.
     *
     * <p>Photo envelope → the {@code full} data-URI payload; document envelope →
     * the {@code file} data-URI payload.
     *
     * @throws DecryptException on a malformed envelope.
     */
    public static byte[] parseEnvelopeBytes(String envelopeJson) {
        Map<String, Object> envelope;
        try {
            envelope = Json.parseObject(envelopeJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
            throw new DecryptException("binary envelope is not valid JSON", exc);
        }
        String dataUri = null;
        for (String key : DATA_URI_KEYS) {
            Object v = envelope.get(key);
            if (v instanceof String s) {
                dataUri = s;
                break;
            }
        }
        if (dataUri == null) {
            throw new DecryptException("binary envelope has no 'full'/'file' data-URI payload");
        }
        // data:<mime>;base64,<payload>
        int idx = dataUri.indexOf("base64,");
        if (idx == -1) {
            throw new DecryptException("binary data URI is not base64-encoded");
        }
        String payload = dataUri.substring(idx + "base64,".length());
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException exc) {
            throw new DecryptException("binary data-URI payload is not valid base64", exc);
        }
    }

    /** Fetch (if needed), decrypt, and return the decoded primary file bytes. */
    public byte[] bytes() {
        return parseEnvelopeBytes(resolveEnvelope());
    }

    /**
     * Write the decoded file bytes to {@code path}; return the number of bytes written.
     *
     * <p>Crash-safe (matching the buffer's atomic-write discipline): the
     * bytes are written to a temp file in the same directory, fsync'd, and atomically
     * moved into place — so a crash mid-write never leaves a truncated output file.
     */
    public long save(Path path) {
        byte[] data = bytes();
        Path dir = path.toAbsolutePath().getParent();
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, ".tmp_", ".part");
        } catch (IOException exc) {
            throw new RuntimeException("could not create temp file for save: " + exc.getMessage(), exc);
        }
        try {
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                OutputStream out = java.nio.channels.Channels.newOutputStream(ch);
                out.write(data);
                out.flush();
                ch.force(true); // fsync data + metadata
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exc) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
            throw new RuntimeException("could not save binary to " + path + ": " + exc.getMessage(), exc);
        }
        return data.length;
    }

    /** Convenience overload accepting a path string. */
    public long save(String path) {
        return save(Path.of(path));
    }
}
