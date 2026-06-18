package fyi.allme.allus.companydata;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The cross-language crypto-parity GATE.
 *
 * <p>Proves the Java decryptor reproduces the SHARED test vector
 * ({@code sdks/testdata/decryption-vector.json}):
 * <ol>
 *   <li>load the PBES2 service PEM (PBKDF2-HMAC-SHA256 + AES-256-CBC, 100k iters)
 *       with the vector's passphrase — via BouncyCastle;</li>
 *   <li>decrypt the text wrapper (RSA-OAEP SHA-256 incl. MGF1-SHA256 → AES-256-GCM,
 *       12-byte IV, 16-byte tag appended) to the exact plaintext;</li>
 *   <li>decrypt the binary wrapper → JSON envelope (sha256 matches) → inner base64
 *       → bytes whose sha256 matches the vector.</li>
 * </ol>
 *
 * <p>If this passes, the port's crypto is correct (the two JCE footguns — OAEP
 * MGF1 default-SHA1 and PBES2 PEM load — are handled in {@link Crypto}).
 */
class CryptoVectorTest {
    private static Map<String, Object> vector;
    private static RSAPrivateKey privateKey;

    @BeforeAll
    static void setUp() {
        vector = TestData.vector();
        privateKey = Crypto.loadPrivateKey(
            pem().getBytes(StandardCharsets.UTF_8), (String) vector.get("passphrase"));
    }

    private static String pem() {
        return (String) vector.get("encrypted_private_key_pem");
    }

    @SuppressWarnings("unchecked")
    private static Wrapper textWrapper() {
        return Wrapper.of(((Map<String, Object>) vector.get("text")).get("wrapper"));
    }

    @SuppressWarnings("unchecked")
    private static Wrapper binaryWrapper() {
        return Wrapper.of(((Map<String, Object>) vector.get("binary")).get("wrapper"));
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (java.security.NoSuchAlgorithmException exc) {
            throw new IllegalStateException(exc);
        }
    }

    // ── PEM load (gate part a) ──────────────────────────────────────────────

    @Test
    void loadsPbes2EncryptedPem() {
        RSAPrivateKey key = Crypto.loadPrivateKey(
            pem().getBytes(StandardCharsets.UTF_8), (String) vector.get("passphrase"));
        assertEquals(2048, key.getModulus().bitLength());
    }

    @Test
    void wrongPassphraseRaisesDecryptException() {
        assertThrows(DecryptException.class, () ->
            Crypto.loadPrivateKey(pem().getBytes(StandardCharsets.UTF_8), "the-wrong-passphrase"));
    }

    // ── text decrypt (gate part b) ──────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void decryptsTextWrapperToPlaintext() {
        String expected = (String) ((Map<String, Object>) vector.get("text")).get("plaintext");
        assertEquals(expected, Crypto.decrypt(textWrapper(), privateKey));
    }

    @Test
    @SuppressWarnings("unchecked")
    void decryptAcceptsWrapperAsJsonString() {
        Map<String, Object> w = (Map<String, Object>)
            ((Map<String, Object>) vector.get("text")).get("wrapper");
        String expected = (String) ((Map<String, Object>) vector.get("text")).get("plaintext");
        String json = fyi.allme.allus.companydata.internal.Json.write(w);
        assertEquals(expected, Crypto.decrypt(Wrapper.of(json), privateKey));
    }

    // ── binary decrypt → envelope → inner bytes (gate part c) ───────────────

    @Test
    @SuppressWarnings("unchecked")
    void decryptsBinaryWrapperToEnvelopeAndInnerBytes() {
        Map<String, Object> binary = (Map<String, Object>) vector.get("binary");

        // 1) decrypt → JSON envelope STRING; its sha256 matches the vector.
        String envelopeJson = Crypto.decrypt(binaryWrapper(), privateKey);
        assertEquals(
            binary.get("decrypted_json_sha256"),
            sha256Hex(envelopeJson.getBytes(StandardCharsets.UTF_8)));

        // 2) parse envelope → base64-decode "full"/"file" → inner file bytes.
        byte[] inner = BinaryHandle.parseEnvelopeBytes(envelopeJson);
        assertEquals(binary.get("inner_full_sha256"), sha256Hex(inner));

        // 3) via the handle's public .bytes() entry point.
        BinaryHandle handle = BinaryHandle.fromEnvelope(envelopeJson);
        assertEquals(binary.get("inner_full_sha256"), sha256Hex(handle.bytes()));
    }

    // ── error paths ─────────────────────────────────────────────────────────

    @Test
    void tagMismatchRaises() {
        Wrapper w = textWrapper();
        byte[] raw = Base64.getDecoder().decode(w.d());
        raw[raw.length - 1] ^= 0xFF; // corrupt the last byte of the GCM tag
        Wrapper bad = new Wrapper(w.k(), w.iv(), Base64.getEncoder().encodeToString(raw));
        assertThrows(DecryptException.class, () -> Crypto.decrypt(bad, privateKey));
    }

    @Test
    void missingFieldRaises() {
        assertThrows(DecryptException.class, () ->
            Wrapper.of(Map.of("_enc", 1, "k", "AAAA", "iv", "AAAA"))); // no "d"
    }

    @Test
    void badBase64Raises() {
        Wrapper w = textWrapper();
        Wrapper bad = new Wrapper("not valid base64 !!!", w.iv(), w.d());
        assertThrows(DecryptException.class, () -> Crypto.decrypt(bad, privateKey));
    }

    @Test
    void wrongIvLengthRaises() {
        Wrapper w = textWrapper();
        Wrapper bad = new Wrapper(w.k(), Base64.getEncoder().encodeToString(new byte[16]), w.d());
        assertThrows(DecryptException.class, () -> Crypto.decrypt(bad, privateKey));
    }

    @Test
    void parseEnvelopeWithoutFullOrFileRaises() {
        assertThrows(DecryptException.class, () ->
            BinaryHandle.parseEnvelopeBytes("{\"thumb\":\"x\"}"));
    }

    // ── BinaryHandle.save() atomic ──────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void saveWritesBytesAndCount(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Map<String, Object> binary = (Map<String, Object>) vector.get("binary");
        String envelopeJson = Crypto.decrypt(binaryWrapper(), privateKey);
        BinaryHandle handle = BinaryHandle.fromEnvelope(envelopeJson);
        Path out = tmp.resolve("out.bin");
        long n = handle.save(out);
        byte[] data = Files.readAllBytes(out);
        assertEquals(data.length, n);
        assertEquals(binary.get("inner_full_sha256"), sha256Hex(data));
    }

    @Test
    void saveAtomicNoPartialOnExistingFile(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // save() into an existing file replaces it atomically; if it fails, the
        // original survives and no temp file leaks. We can't easily fault-inject
        // the JDK write, so we prove the success path replaces cleanly + leaves no
        // .tmp_ leftover (the failure cleanup is covered by code inspection).
        String envelopeJson = Crypto.decrypt(binaryWrapper(), privateKey);
        BinaryHandle handle = BinaryHandle.fromEnvelope(envelopeJson);
        Path dest = tmp.resolve("existing.bin");
        Files.write(dest, "ORIGINAL".getBytes(StandardCharsets.UTF_8));
        handle.save(dest);
        // overwritten with the binary content, no temp leftovers
        assertArrayEquals(BinaryHandle.parseEnvelopeBytes(envelopeJson), Files.readAllBytes(dest));
        try (var s = Files.list(tmp)) {
            assertEquals(0, s.filter(p -> p.getFileName().toString().startsWith(".tmp_")).count());
        }
    }
}
