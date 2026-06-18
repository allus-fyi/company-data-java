package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;
import fyi.allme.allus.companydata.internal.ModelDeps;
import fyi.allme.allus.companydata.internal.Xml;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.spec.MGF1ParameterSpec;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * Webhook receiver helpers.
 *
 * <p>The lower-latency push alternative to polling the changes feed. The platform
 * delivers each change event to the company's configured webhook URL with:
 * <ul>
 *   <li>{@code X-Allus-Webhook-Id} — which webhook this is (selects the HMAC secret);</li>
 *   <li>{@code X-Allus-Signature} — {@code HMAC-SHA256(rawBody, secret)} as lowercase hex;</li>
 *   <li>the body — the same slug-keyed {@link Change} shape as the pull feed, JSON or XML.
 *       If the webhook has {@code encrypt_payload} on, the body is REPLACED by a
 *       {@code {"_enc":1,...}} envelope encrypted to the company <b>account</b> key
 *       (and the HMAC is then over that envelope — the final body sent).</li>
 * </ul>
 *
 * <p>All secrets/keys come from {@link Config}. <b>These helpers take
 * NO key or secret arguments</b> — only the raw body, the headers, the config, and
 * (for value typing) the same decrypt/type closures the {@link Client} already holds.
 *
 * <p>The account-key envelope is webhook-specific: the platform wraps it with
 * OpenSSL's DEFAULT OAEP padding (MGF1-<b>SHA1</b>), NOT the SHA-256 wrapper used
 * for person field values. So unwrapping the envelope uses an OAEP-SHA1 path here,
 * while the inner field {@code value} (a service-key wrapper) decrypts with the
 * normal SHA-256 {@link Crypto#decrypt}.
 */
public final class Webhooks {
    private static final String HDR_WEBHOOK_ID = "x-allus-webhook-id";
    private static final String HDR_SIGNATURE = "x-allus-signature";

    private Webhooks() {
    }

    // ── header helpers ───────────────────────────────────────────────────────

    private static String header(Map<String, ?> headers, String name) {
        if (headers == null) {
            return null;
        }
        String target = name.toLowerCase();
        for (Map.Entry<String, ?> e : headers.entrySet()) {
            if (e.getKey() != null && e.getKey().toLowerCase().equals(target)) {
                Object v = e.getValue();
                if (v == null) {
                    return null;
                }
                if (v instanceof java.util.List<?> list) {
                    return list.isEmpty() ? null : String.valueOf(list.get(0));
                }
                return String.valueOf(v);
            }
        }
        return null;
    }

    private static byte[] asBytes(Object rawBody) {
        if (rawBody instanceof byte[] b) {
            return b;
        }
        if (rawBody instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        throw new WebhookException("webhook rawBody must be byte[] or String");
    }

    // ── verify ─────────────────────────────────────────────────────────────────

    /**
     * Verify a webhook against the SINGLE configured auth method.
     *
     * <p>Mirrors the platform's per-webhook delivery auth (one method per webhook):
     * <ul>
     *   <li>{@code hmac}   — recompute {@code HMAC-SHA256(rawBody, secret)} (secret selected
     *       by {@code X-Allus-Webhook-Id}) and constant-time-compare to {@code X-Allus-Signature};</li>
     *   <li>{@code bearer} — {@code Authorization} equals {@code Bearer <token>};</li>
     *   <li>{@code basic}  — {@code Authorization} equals {@code Basic <base64(user:pass)>};</li>
     *   <li>{@code header} — the configured custom header equals the configured value;</li>
     *   <li>{@code none}   — always {@code true} (explicit opt-out).</li>
     * </ul>
     *
     * <p>All comparisons are constant-time. Returns {@code false} on a missing/mismatched
     * credential, or when no method is configured — never raises for a bad credential
     * (that is {@link #handleWebhook}'s job). Which method is used is decided entirely by
     * config ({@link Config#webhookAuthMethod()}); config loading guarantees at most one
     * is set.
     */
    public static boolean verifyWebhook(Object rawBody, Map<String, ?> headers, Config config) {
        String method = config.webhookAuthMethod();
        if (method == null) {
            return false;
        }
        if (method.equals("none")) {
            return true;
        }

        if (method.equals("bearer")) {
            String got = header(headers, "authorization");
            if (got == null) {
                return false;
            }
            String token = config.webhookBearerToken() != null ? config.webhookBearerToken() : "";
            return constantTimeEquals(got, "Bearer " + token);
        }

        if (method.equals("basic")) {
            String got = header(headers, "authorization");
            if (got == null) {
                return false;
            }
            String creds = config.webhookBasic().get("username") + ":" + config.webhookBasic().get("password");
            String token = Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
            return constantTimeEquals(got, "Basic " + token);
        }

        if (method.equals("header")) {
            String got = header(headers, config.webhookHeader().get("name"));
            if (got == null) {
                return false;
            }
            return constantTimeEquals(got, config.webhookHeader().get("value"));
        }

        // method == "hmac"
        byte[] body = asBytes(rawBody);
        String signature = header(headers, HDR_SIGNATURE);
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        String webhookId = header(headers, HDR_WEBHOOK_ID);
        String secret = config.webhookSecret(webhookId);
        if (secret == null || secret.isEmpty()) {
            return false;
        }
        String expected = hmacSha256Hex(secret, body);
        // Constant-time compare (case-insensitive hex, like the platform's output).
        byte[] a = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] b = signature.strip().toLowerCase().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(a, b);
    }

    /** Timing-safe string comparison (different lengths return false). */
    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (java.security.GeneralSecurityException exc) {
            throw new WebhookException("could not compute HMAC: " + exc.getMessage(), exc);
        }
    }

    // ── parse ──────────────────────────────────────────────────────────────────

    /**
     * Parse a webhook body → a typed {@link Change}.
     *
     * <p>Does NOT verify the signature (use {@link #handleWebhook} for verify+parse).
     * Handles JSON and XML bodies, and an {@code encrypt_payload} account-key
     * envelope: if the (JSON) body is a {@code {"_enc":1,...}} wrapper, it is first
     * unwrapped with the account private key (OAEP-SHA1) into the inner serialized
     * payload, which is then parsed. The inner field {@code value} (a service-key
     * wrapper) is decrypted by the same model path the feed uses, so a webhook
     * {@link Change} is identical to a feed {@link Change}.
     *
     * <p>{@code accountKey} is an optional pre-loaded account private key (the
     * {@link Client} loads it ONCE and reuses it, so an {@code encrypt_payload}
     * webhook doesn't re-read the PEM + re-run PBKDF2 ~100k iters per request). When
     * {@code null}, the key is loaded from config on demand.
     */
    public static Change parseWebhook(Object rawBody, Map<String, ?> headers, Config config,
                                      ModelDeps deps, RSAPrivateKey accountKey) {
        byte[] body = asBytes(rawBody);
        Object payload = decodePayload(body, config, accountKey);
        if (!(payload instanceof Map<?, ?> map)) {
            throw new WebhookException("webhook payload is not a JSON/XML object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return Change.fromApi(typed, deps);
    }

    /**
     * Verify + parse a webhook in one call.
     *
     * @throws WebhookException on a bad/unknown signature, or an unparseable body/envelope.
     */
    public static Change handleWebhook(Object rawBody, Map<String, ?> headers, Config config,
                                       ModelDeps deps, RSAPrivateKey accountKey) {
        if (!verifyWebhook(rawBody, headers, config)) {
            throw new WebhookException("webhook signature verification failed");
        }
        return parseWebhook(rawBody, headers, config, deps, accountKey);
    }

    // ── payload decoding (JSON / XML / encrypt_payload envelope) ─────────────────

    private static Object decodePayload(byte[] body, Config config, RSAPrivateKey accountKey) {
        String text = new String(body, StandardCharsets.UTF_8).strip();

        if (text.startsWith("{")) {
            Object obj;
            try {
                obj = Json.parse(text);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
                throw new WebhookException("webhook body is not valid JSON: " + exc.getMessage(), exc);
            }
            if (obj instanceof Map<?, ?> m && isEnvelope(m)) {
                String inner = unwrapAccountEnvelope(m, config, accountKey);
                return decodeInner(inner);
            }
            return obj;
        }
        if (text.startsWith("<")) {
            try {
                return Xml.parse(text);
            } catch (Exception exc) {
                throw new WebhookException("webhook body is not valid XML: " + exc.getMessage(), exc);
            }
        }
        throw new WebhookException("webhook body is neither JSON nor XML");
    }

    private static boolean isEnvelope(Map<?, ?> m) {
        Object enc = m.get("_enc");
        boolean encOne = (enc instanceof Number n && n.intValue() == 1)
            || "1".equals(String.valueOf(enc));
        return encOne && m.containsKey("k") && m.containsKey("iv") && m.containsKey("d");
    }

    private static Object decodeInner(String innerText) {
        String stripped = innerText.strip();
        if (stripped.startsWith("<")) {
            try {
                return Xml.parse(stripped);
            } catch (Exception exc) {
                throw new WebhookException("decrypted webhook payload is not valid XML: " + exc.getMessage(), exc);
            }
        }
        try {
            return Json.parse(stripped);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exc) {
            throw new WebhookException("decrypted webhook payload is not valid JSON: " + exc.getMessage(), exc);
        }
    }

    // ── account-key envelope unwrap (OAEP-SHA1 — webhook-specific) ───────────────

    /**
     * Load the account private key from config ONCE (or {@code null} if not configured).
     *
     * <p>Reused by the {@link Client} so an {@code encrypt_payload} webhook never
     * re-reads the account PEM + re-runs PBKDF2 (~100k iters) per request. Returns
     * {@code null} when no {@code account_private_key} is configured.
     *
     * @throws WebhookException on a read / passphrase / PEM problem.
     */
    public static RSAPrivateKey loadAccountKey(Config config) {
        if (config.accountPrivateKey() == null || config.accountPrivateKey().isEmpty()) {
            return null;
        }
        byte[] pem;
        try {
            pem = Files.readAllBytes(Path.of(config.accountPrivateKey()));
        } catch (IOException exc) {
            throw new WebhookException("could not read account_private_key PEM: "
                + config.accountPrivateKey() + ": " + exc.getMessage(), exc);
        }
        String passphrase = config.accountPassphrase() != null ? config.accountPassphrase() : "";
        try {
            return Crypto.loadPrivateKey(pem, passphrase);
        } catch (DecryptException exc) {
            throw new WebhookException("could not load account private key: " + exc.getMessage(), exc);
        }
    }

    private static String unwrapAccountEnvelope(Map<?, ?> envelope, Config config, RSAPrivateKey accountKey) {
        RSAPrivateKey key = accountKey != null ? accountKey : loadAccountKey(config);
        if (key == null) {
            throw new WebhookException(
                "received an encrypt_payload webhook but no account_private_key is configured");
        }
        return decryptOaepSha1(envelope, key);
    }

    /**
     * RSA-OAEP(<b>SHA-1</b>, MGF1-SHA1) unwrap + AES-256-GCM decrypt → UTF-8 string.
     *
     * <p>Mirrors {@link Crypto#decrypt} but pins SHA-1 for the OAEP/MGF1 hash to
     * match the account-key envelope (the account-key envelope uses
     * {@code OPENSSL_PKCS1_OAEP_PADDING}, OpenSSL's default MGF1-SHA1).
     */
    private static String decryptOaepSha1(Map<?, ?> wrapper, RSAPrivateKey privateKey) {
        byte[] encKey = b64(wrapper.get("k"), "k");
        byte[] iv = b64(wrapper.get("iv"), "iv");
        byte[] ciphertextWithTag = b64(wrapper.get("d"), "d");

        if (iv.length != Crypto.GCM_IV_LEN) {
            throw new WebhookException("envelope iv must be " + Crypto.GCM_IV_LEN + " bytes, got " + iv.length);
        }
        if (ciphertextWithTag.length < Crypto.GCM_TAG_LEN) {
            throw new WebhookException("envelope ciphertext too short to contain a GCM tag");
        }
        byte[] aesKey;
        try {
            aesKey = Crypto.rsaOaepDecrypt(encKey, privateKey, "SHA-1", MGF1ParameterSpec.SHA1);
        } catch (DecryptException exc) {
            throw new WebhookException(
                "account-key envelope RSA-OAEP unwrap failed (wrong account key?): " + exc.getMessage(), exc);
        }
        if (aesKey.length != 32) {
            throw new WebhookException("unwrapped envelope AES key must be 32 bytes, got " + aesKey.length);
        }
        byte[] plaintext;
        try {
            plaintext = Crypto.aesGcmDecrypt(aesKey, iv, ciphertextWithTag);
        } catch (DecryptException exc) {
            throw new WebhookException("account-key envelope AES-GCM tag mismatch", exc);
        }
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private static byte[] b64(Object value, String name) {
        if (!(value instanceof String s)) {
            throw new WebhookException("envelope field '" + name + "' must be a base64 string");
        }
        try {
            return Base64.getDecoder().decode(s);
        } catch (IllegalArgumentException exc) {
            throw new WebhookException("envelope field '" + name + "' is not valid base64", exc);
        }
    }
}
