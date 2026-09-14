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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Lazy handle for a binary (photo/document) value.
 *
 * <p>A binary answer is stored server-side as a file, exposed in the hardened API
 * as a slot-keyed {@code value_url} (never the source field). {@link #bytes()} and
 * {@link #save(Path)} GET that URL and return the FILE BYTES; {@link #pages()} and
 * {@link #metadata()} expose the rest of the envelope. The caller never has to know
 * which of the three response shapes arrived.
 *
 * <p>THERE ARE THREE SHAPES, AND WHICH ONE ARRIVES IS NOT THE COMPANY'S CHOICE. The
 * person's own privacy setting and the TYPE of the field they answered with decide
 * it, either can change at any time, and nothing in the API announces it in advance:
 * <ul>
 *   <li><b>private source</b> → {@code application/json}
 *       {@code {"encrypted":true,"value":<wrapper>}}. The wrapper decrypts to a JSON
 *       envelope STRING (photo: {@code {"full":"data:...","thumb":...}}; single-file
 *       document: {@code {"file":"data:...",...}}; multi-page document:
 *       {@code {"pages":[{"file":"data:...",...}],...}}) — NOT raw bytes.</li>
 *   <li><b>non-private source whose type stores pages or declares entries</b> →
 *       {@code application/json} {@code {"encrypted":false,"value":"<envelope>"}}. The
 *       same envelope string, in the clear. There is nothing to decrypt.</li>
 *   <li><b>every other non-private source</b> → the file's own {@code Content-Type}
 *       and the body IS the file. A handle built this way needs no service key at
 *       all.</li>
 * </ul>
 *
 * <p>Photos resolve to the {@code full} representation. There is no variant selection.
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
 *
 * <p>{@link #bytes()}, {@link #pages()} and {@link #metadata()} share ONE lazy fetch:
 * whichever is called first performs it, and every later call answers from the parsed
 * envelope.
 */
public final class BinaryHandle {
    /** Envelope keys holding the primary binary data URI, in priority order. */
    private static final List<String> DATA_URI_KEYS = List.of("full", "file");

    /**
     * Envelope members that describe the envelope itself rather than the type's own declared
     * entries — everything NOT in this set is metadata.
     */
    private static final Set<String> ENVELOPE_MEMBERS =
        Set.of("pages", "file", "full", "thumb", "original_name", "mime_type", "size");

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
     * The platform's {@code X-Allus-Content-Sha256} — the digest of the SERVED ARTIFACT.
     *
     * <p>Which artifact that is follows the response arm: the raw bytes when the answer arrived as
     * bytes, and the served {@code value} string on either JSON arm — the ciphertext wrapper for a
     * private source, the plaintext envelope for a non-private one. It is NOT "the sha256 of what
     * {@link #bytes()} returns": on an envelope carrying pages {@link #bytes()} throws, and on an
     * envelope carrying one file it returns the decoded payload rather than the envelope string.
     *
     * <p>A consumer can record it and later show that its archived copy has not drifted.
     * {@code null} until something has been fetched, and on a handle built from an envelope that
     * was never fetched through this class.
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
            // handle built without one on exactly the answers that do not need it. The envelope arm
            // is plaintext too — the same envelope string the wrapper arm decrypts to — so both
            // JSON arms converge here.
            if (result.envelope() != null) {
                this.envelopeJson = result.envelope();
                return;
            }
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

    /** The ONE envelope parser both JSON arms go through. */
    private static Map<String, Object> parseEnvelope(String envelopeJson) {
        try {
            return Json.parseObject(envelopeJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
            throw new DecryptException("binary envelope is not valid JSON", exc);
        }
    }

    /** {@code data:<mime>;base64,<payload>} → the decoded payload. */
    private static byte[] decodeDataUri(String dataUri) {
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
     * Turn a decrypted binary envelope STRING into the primary file bytes.
     *
     * <p>Photo envelope → the {@code full} data-URI payload; single-file document envelope →
     * the {@code file} data-URI payload. A MULTI-PAGE envelope has no single primary file, so it
     * throws rather than handing back the first page as though it were the whole document.
     *
     * @throws DecryptException on a malformed envelope.
     */
    public static byte[] parseEnvelopeBytes(String envelopeJson) {
        Map<String, Object> envelope = parseEnvelope(envelopeJson);
        String dataUri = null;
        for (String key : DATA_URI_KEYS) {
            Object v = envelope.get(key);
            if (v instanceof String s) {
                dataUri = s;
                break;
            }
        }
        if (dataUri == null) {
            if (envelope.get("pages") instanceof List<?> pages && !pages.isEmpty()) {
                throw new DecryptException("multi-page envelope: use pages");
            }
            throw new DecryptException("binary envelope has no 'full'/'file' data-URI payload");
        }
        return decodeDataUri(dataUri);
    }

    /**
     * The parsed envelope, fetching+decrypting on first use. {@code null} when the answer is
     * plaintext BYTES, which carries no envelope at all.
     */
    private Map<String, Object> envelopeOrNull() {
        if (envelopeJson == null) {
            fetchOnce();
            if (envelopeJson == null) {
                return null;
            }
        }
        return parseEnvelope(envelopeJson);
    }

    /**
     * The envelope's pages, in envelope order — an empty list for a single-file envelope.
     *
     * <p>Lazy exactly as {@link #bytes()} is: the first call of {@code bytes}, {@code pages} or
     * {@code metadata} performs the one fetch and optional decrypt, and every later call answers
     * from the parsed envelope. A handle built from an envelope string needs no fetch. A
     * plaintext-BYTES answer carries no envelope, so it has no pages.
     *
     * @throws DecryptException on a failed fetch or decrypt, or a malformed envelope.
     */
    public List<BinaryPage> pages() {
        Map<String, Object> envelope = envelopeOrNull();
        if (envelope == null || !(envelope.get("pages") instanceof List<?> raw)) {
            return List.of();
        }
        List<BinaryPage> out = new ArrayList<>(raw.size());
        for (Object entry : raw) {
            if (!(entry instanceof Map<?, ?> page) || !(page.get("file") instanceof String uri)) {
                throw new DecryptException("binary envelope page has no data-URI payload");
            }
            out.add(new BinaryPage(
                page.get("label") instanceof String label ? label : null,
                page.get("original_name") instanceof String name ? name : null,
                page.get("mime_type") instanceof String mime ? mime : null,
                decodeDataUri(uri)));
        }
        return List.copyOf(out);
    }

    /**
     * Every declared entry the envelope carries, as a plain map.
     *
     * <p>Keys are every envelope member other than the envelope's own ({@code pages}, {@code file},
     * {@code full}, {@code thumb}, {@code original_name}, {@code mime_type}, {@code size}); values
     * are the stored string, or {@code null} for an entry the person left unset. {@code name} — the
     * holder name an ID provider extracted — is a member like any other and appears here.
     *
     * <p><b>The map carries no ordering guarantee.</b> A consumer that needs the type's declared
     * order reads the envelope string itself. It permits null values, so it is a
     * {@link LinkedHashMap} rather than {@code Map.of(…)}.
     *
     * <p>Empty for a photo, for a plain document that declares no entries, and for a
     * plaintext-BYTES answer. Lazy exactly as {@link #pages()} is.
     *
     * @throws DecryptException on a failed fetch or decrypt, or a malformed envelope.
     */
    public Map<String, String> metadata() {
        Map<String, Object> envelope = envelopeOrNull();
        Map<String, String> out = new LinkedHashMap<>();
        if (envelope == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : envelope.entrySet()) {
            if (ENVELOPE_MEMBERS.contains(entry.getKey())) {
                continue;
            }
            out.put(entry.getKey(), entry.getValue() instanceof String s ? s : null);
        }
        return out;
    }

    /**
     * Fetch (if needed), decrypt (if needed), and return the primary file bytes — the same bytes
     * whichever response shape arrived, so callers never branch on it themselves. A MULTI-PAGE
     * envelope has no single primary file and throws: use {@link #pages()}.
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
