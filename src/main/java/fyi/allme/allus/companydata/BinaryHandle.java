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
 * as a slot-keyed {@code value_url} (never the source field). {@link #bytes()} and
 * {@link #save(Path)} GET that URL and return the FILE BYTES either way — the caller
 * never has to know which of the two response shapes arrived.
 *
 * <p>THERE ARE TWO SHAPES, AND WHICH ONE ARRIVES IS THE PERSON'S CHOICE, NOT
 * THE COMPANY'S. Whether the person's source field is private decides it, they can
 * change it at any time, and nothing in the API announces it in advance:
 * <ul>
 *   <li><b>private source</b> → {@code application/json}
 *       {@code {"encrypted":true,"value":<wrapper>}}. The wrapper decrypts to a JSON
 *       envelope STRING (photo: {@code {"full":"data:...","thumb":...}}; document:
 *       {@code {"file":"data:...",...}}) — NOT raw bytes — whose primary data-URI
 *       payload ({@code full} for photos, {@code file} for documents) base64-decodes
 *       to the file.</li>
 *   <li><b>plaintext source</b> → the file's own {@code Content-Type} and the body IS
 *       the file. There is nothing to decrypt, and a handle built this way needs no
 *       service key at all.</li>
 * </ul>
 *
 * <p>Photos resolve to the {@code full} representation. There is no variant selection:
 * one slot has one byte sequence and therefore one digest.
 *
 * <p>The fetch + decrypt are supplied by the client as plain callables (config-only
 * key handling — no key is ever passed to this handle):
 * <ul>
 *   <li>{@code valueUrl} + {@code fetch} — {@code fetch.apply(valueUrl)} returns a
 *       {@link BinaryFetchResult} saying which shape arrived (the client classifies it
 *       on the response's {@code Content-Type}; the body is never sniffed).</li>
 *   <li>{@code decrypt} — {@code decrypt.apply(wrapper)} returns the decrypted
 *       envelope string (a closure over the loaded service private key). Only ever
 *       called for the encrypted shape.</li>
 * </ul>
 *
 * <p>For the shared crypto test vector the decrypted envelope is already in hand, so
 * a handle can also be built directly via {@link #fromEnvelope(String)}.
 */
public final class BinaryHandle {
    /** Envelope keys holding the primary binary data URI, in priority order. */
    private static final List<String> DATA_URI_KEYS = List.of("full", "file");

    private String envelopeJson;            // cached once resolved
    /** Plaintext file bytes, once a plaintext-shaped response has been fetched. */
    private byte[] plainBytes;
    private String contentType;
    private String contentSha256;
    private final String valueUrl;
    private final Function<String, BinaryFetchResult> fetch;
    private final Function<Wrapper, String> decrypt;

    private BinaryHandle(String envelopeJson, String valueUrl,
                         Function<String, BinaryFetchResult> fetch, Function<Wrapper, String> decrypt) {
        this.envelopeJson = envelopeJson;
        this.valueUrl = valueUrl;
        this.fetch = fetch;
        this.decrypt = decrypt;
    }

    /** A handle whose decrypted envelope is already in hand (test vector / inline). */
    public static BinaryHandle fromEnvelope(String envelopeJson) {
        return new BinaryHandle(envelopeJson, null, null, null);
    }

    /** A lazy handle that fetches (and decrypts, if needed) on first {@link #bytes()} / {@link #save(Path)}. */
    public static BinaryHandle lazy(String valueUrl, Function<String, BinaryFetchResult> fetch,
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

    /**
     * The platform's {@code X-Allus-Content-Sha256} for the bytes this handle fetched — the sha256
     * of exactly what {@link #bytes()} returns, so a consumer can record it and later show that its
     * archived copy has not drifted. {@code null} until something has been fetched, and on a handle
     * built from an envelope that was never fetched through this class.
     *
     * <p>It is the platform's word, not a signature: it proves agreement with the platform's record,
     * not anything to a third party who doubts that record.
     */
    public String contentSha256() {
        return contentSha256;
    }

    /** The response {@code Content-Type} the bytes arrived with, once fetched; may be {@code null}. */
    public String contentType() {
        return contentType;
    }

    /**
     * Fetch once and record which shape arrived. Idempotent: the result is cached on the handle so
     * repeated {@link #bytes()} / {@link #save(Path)} calls do not re-fetch, and so a plaintext
     * answer's digest survives for {@link #contentSha256()}.
     */
    private void fetchOnce() {
        if (plainBytes != null || envelopeJson != null) {
            return;
        }
        if (fetch == null || valueUrl == null) {
            throw new DecryptException(
                "BinaryHandle has no envelope and no fetch wiring "
                    + "(build it with fromEnvelope, or lazy(valueUrl, fetch, decrypt))");
        }
        BinaryFetchResult result = fetch.apply(valueUrl);
        this.contentType = result.contentType();
        this.contentSha256 = result.contentSha256();

        if (!result.encrypted()) {
            // A plaintext answer needs no service key. Demanding `decrypt` here would fail a
            // handle built without one on exactly the answers that do not need it.
            this.plainBytes = result.bytes() != null ? result.bytes() : new byte[0];
            return;
        }
        if (decrypt == null) {
            throw new DecryptException("binary answer is encrypted but this handle has no decrypt wiring");
        }
        this.envelopeJson = decrypt.apply(result.wrapper()); // cached so repeated calls don't re-fetch
    }

    /** Return the decrypted envelope string, fetching+decrypting on first use. */
    private String resolveEnvelope() {
        if (envelopeJson != null) {
            return envelopeJson;
        }
        fetchOnce();
        if (envelopeJson == null) {
            throw new DecryptException("binary answer arrived as plaintext bytes; use bytes()/save()");
        }
        return envelopeJson;
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

    /**
     * Fetch (if needed), decrypt (if needed), and return the primary file bytes — the same bytes for
     * either response shape, so callers never branch on it themselves.
     */
    public byte[] bytes() {
        if (plainBytes != null) {
            return plainBytes;
        }
        if (envelopeJson == null) {
            fetchOnce();
            if (plainBytes != null) {
                return plainBytes;
            }
        }
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
