package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.ModelDeps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Webhook receiver-helper tests. Mirrors the Python reference's test_webhooks. */
class WebhooksTest {
    private static final String SECRET = "wh_secret_abc123";
    private static final String WEBHOOK_ID = "wh-1";

    private static Map<String, Object> vector;
    private static RSAPrivateKey privateKey;
    private static Function<Object, String> decryptValue;

    @BeforeAll
    static void setUp() {
        vector = TestData.vector();
        privateKey = Crypto.loadPrivateKey(
            ((String) vector.get("encrypted_private_key_pem")).getBytes(StandardCharsets.UTF_8),
            (String) vector.get("passphrase"));
        decryptValue = w -> Crypto.decrypt(Wrapper.of(w), privateKey);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> textWrapper() {
        return (Map<String, Object>) ((Map<String, Object>) vector.get("text")).get("wrapper");
    }

    private static String textPlaintext() {
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) vector.get("text");
        return (String) t.get("plaintext");
    }

    private static ModelDeps deps() {
        Map<String, String> types = Map.of("work_email", "email", "logo", "photo");
        return new ModelDeps(decryptValue, types::get, null);
    }

    private static Config config(Path tmp) throws Exception {
        Path pem = tmp.resolve("service-key.pem");
        Files.writeString(pem, (String) vector.get("encrypted_private_key_pem"), StandardCharsets.US_ASCII);
        return Config.builder()
            .apiUrl("https://api.allme.fyi").clientId("svc").clientSecret("s")
            .servicePrivateKey(pem.toString()).keyPassphrase((String) vector.get("passphrase"))
            .cacheDir(tmp.resolve("cache").toString())
            .webhooks(Map.of(WEBHOOK_ID, SECRET))
            .build();
    }

    private static byte[] changeBody() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", "chg-1");
        payload.put("event", "field_updated");
        payload.put("person_user_id", "person-1");
        payload.put("slug", "work_email");
        payload.put("at", "2026-06-17T12:00:00Z");
        payload.put("live", true);
        payload.put("value", textWrapper());
        return fyi.allme.allus.companydata.internal.Json.write(payload).getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> headers(byte[] body, String secret, String webhookId, boolean sign) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Allus-Webhook-Id", webhookId);
        h.put("X-Allus-Event", "field_updated");
        if (sign) {
            h.put("X-Allus-Signature", TestCrypto.hmacHex(secret, body));
        }
        return h;
    }

    private static Map<String, String> headers(byte[] body) {
        return headers(body, SECRET, WEBHOOK_ID, true);
    }

    // ── verify ─────────────────────────────────────────────────────────────────

    @Test
    void verifyTrueWithKnownSecret(@TempDir Path tmp) throws Exception {
        byte[] body = changeBody();
        assertTrue(Webhooks.verifyWebhook(body, headers(body), config(tmp)));
    }

    @Test
    void verifyFalseOnTamperedBody(@TempDir Path tmp) throws Exception {
        byte[] body = changeBody();
        Map<String, String> h = headers(body);
        byte[] tampered = new byte[body.length + 1];
        System.arraycopy(body, 0, tampered, 0, body.length);
        tampered[body.length] = ' ';
        assertFalse(Webhooks.verifyWebhook(tampered, h, config(tmp)));
    }

    @Test
    void verifyFalseOnUnknownWebhookId(@TempDir Path tmp) throws Exception {
        byte[] body = changeBody();
        assertFalse(Webhooks.verifyWebhook(body, headers(body, SECRET, "wh-UNKNOWN", true), config(tmp)));
    }

    @Test
    void verifyFalseOnMissingSignature(@TempDir Path tmp) throws Exception {
        byte[] body = changeBody();
        assertFalse(Webhooks.verifyWebhook(body, headers(body, SECRET, WEBHOOK_ID, false), config(tmp)));
    }

    @Test
    void verifyAcceptsUppercaseHex(@TempDir Path tmp) throws Exception {
        byte[] body = changeBody();
        Map<String, String> h = new LinkedHashMap<>();
        h.put("X-Allus-Webhook-Id", WEBHOOK_ID);
        h.put("X-Allus-Signature", TestCrypto.hmacHex(SECRET, body).toUpperCase());
        assertTrue(Webhooks.verifyWebhook(body, h, config(tmp)));
    }

    @Test
    void verifySingleWebhookShortcut(@TempDir Path tmp) throws Exception {
        Path pem = tmp.resolve("k.pem");
        Files.writeString(pem, (String) vector.get("encrypted_private_key_pem"), StandardCharsets.US_ASCII);
        Config cfg = Config.builder()
            .apiUrl("https://api.allme.fyi").clientId("svc").clientSecret("s")
            .servicePrivateKey(pem.toString()).keyPassphrase((String) vector.get("passphrase"))
            .cacheDir(tmp.resolve("c").toString())
            .webhookSecret(SECRET) // flat shortcut
            .build();
        byte[] body = changeBody();
        // Header carries an id, but config has only the flat secret → falls back to it.
        assertTrue(Webhooks.verifyWebhook(body, headers(body), cfg));
    }

    // ── parse (plain JSON / XML) ─────────────────────────────────────────────────

    @Test
    void parsePlainJsonBody(@TempDir Path tmp) throws Exception {
        byte[] body = changeBody();
        Change change = Webhooks.parseWebhook(body, headers(body), config(tmp), deps(), null);
        assertEquals("chg-1", change.id());
        assertEquals("field_updated", change.event());
        assertEquals("person-1", change.personId());
        assertEquals("work_email", change.slug());
        assertEquals(textPlaintext(), change.value());
        assertEquals(Boolean.TRUE, change.live());
    }

    @Test
    void parseXmlBody(@TempDir Path tmp) throws Exception {
        Map<String, Object> w = textWrapper();
        String xml = "<response>"
            + "<id>chg-7</id><event>field_updated</event>"
            + "<person_user_id>person-1</person_user_id><slug>work_email</slug>"
            + "<at>2026-06-17T12:00:00Z</at><live>true</live>"
            + "<value><_enc>1</_enc><k>" + w.get("k") + "</k><iv>" + w.get("iv")
            + "</iv><d>" + w.get("d") + "</d></value>"
            + "</response>";
        byte[] body = xml.getBytes(StandardCharsets.UTF_8);
        Change change = Webhooks.parseWebhook(body, headers(body), config(tmp), deps(), null);
        assertEquals("chg-7", change.id());
        assertEquals("field_updated", change.event());
        assertEquals("work_email", change.slug());
        assertEquals(textPlaintext(), change.value());
    }

    // ── parse (account-key encrypt_payload envelope, OAEP-SHA1) ─────────────────

    @Test
    void parseAccountKeyEnvelope(@TempDir Path tmp) throws Exception {
        KeyPair account = TestCrypto.generateRsa2048();
        Path accountPem = tmp.resolve("account.pem");
        Files.writeString(accountPem, TestCrypto.encryptedPkcs8Pem(account.getPrivate(), "acctpp"),
            StandardCharsets.US_ASCII);
        Path servicePem = tmp.resolve("service-key.pem");
        Files.writeString(servicePem, (String) vector.get("encrypted_private_key_pem"), StandardCharsets.US_ASCII);

        Config cfg = Config.builder()
            .apiUrl("https://api.allme.fyi").clientId("svc").clientSecret("s")
            .servicePrivateKey(servicePem.toString()).keyPassphrase((String) vector.get("passphrase"))
            .accountPrivateKey(accountPem.toString()).accountPassphrase("acctpp")
            .cacheDir(tmp.resolve("c").toString()).webhooks(Map.of(WEBHOOK_ID, SECRET))
            .build();

        byte[] inner = changeBody();
        Map<String, Object> envelope = TestCrypto.wrapToAccountKey(
            (java.security.interfaces.RSAPublicKey) account.getPublic(), inner);
        byte[] body = fyi.allme.allus.companydata.internal.Json.write(envelope).getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = headers(body); // HMAC over the envelope (the final body)

        assertTrue(Webhooks.verifyWebhook(body, h, cfg));
        Change change = Webhooks.parseWebhook(body, h, cfg, deps(), null);
        assertEquals("chg-1", change.id());
        assertEquals("field_updated", change.event());
        assertEquals("work_email", change.slug());
        // Outer envelope = account-key (SHA-1); inner value = service-key (SHA-256).
        assertEquals(textPlaintext(), change.value());
    }

    @Test
    void parseAccountEnvelopeWithoutAccountKeyRaises(@TempDir Path tmp) throws Exception {
        KeyPair account = TestCrypto.generateRsa2048();
        byte[] body = fyi.allme.allus.companydata.internal.Json.write(
            TestCrypto.wrapToAccountKey((java.security.interfaces.RSAPublicKey) account.getPublic(), changeBody()))
            .getBytes(StandardCharsets.UTF_8);
        // config() has NO account_private_key → WebhookException.
        assertThrows(WebhookException.class, () ->
            Webhooks.parseWebhook(body, headers(body), config(tmp), deps(), null));
    }

    // ── handle = verify + parse ─────────────────────────────────────────────────

    @Test
    void handleVerifyThenParse(@TempDir Path tmp) throws Exception {
        byte[] body = changeBody();
        Change change = Webhooks.handleWebhook(body, headers(body), config(tmp), deps(), null);
        assertEquals("chg-1", change.id());
    }

    @Test
    void handleBadSignatureRaises(@TempDir Path tmp) throws Exception {
        byte[] body = changeBody();
        Map<String, String> h = headers(body);
        h.put("X-Allus-Signature", "deadbeef");
        assertThrows(WebhookException.class, () ->
            Webhooks.handleWebhook(body, h, config(tmp), deps(), null));
    }

    // ── Client method delegation ────────────────────────────────────────────────

    @Test
    void clientMethodsDelegate(@TempDir Path tmp) throws Exception {
        Config cfg = config(tmp);
        int[] catalogCalls = {0};
        // A transport that returns the token + only answers /request-fields.
        fyi.allme.allus.companydata.internal.Transport catalogOnly =
            new fyi.allme.allus.companydata.internal.Transport() {
                @Override
                public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
                    return FakeTransport.tokenOk();
                }

                @Override
                public Response get(String url, Map<String, String> params, Map<String, String> headers) {
                    assertTrue(url.endsWith("/request-fields"), "unexpected GET " + url);
                    catalogCalls[0]++;
                    return FakeTransport.json(200, "{\"request_fields\":[{\"slug\":\"work_email\","
                        + "\"label\":\"Work email\",\"type\":\"email\",\"one_time\":false,"
                        + "\"mandatory_provide\":true,\"mandatory_connected\":false}]}");
                }
            };

        Client client = new Client(cfg, catalogOnly);
        byte[] body = changeBody();
        Map<String, String> h = headers(body);

        assertTrue(client.verifyWebhook(body, h)); // verify makes NO HTTP
        assertEquals(0, catalogCalls[0]);

        Change change = client.handleWebhook(body, h);
        assertEquals("chg-1", change.id());
        assertEquals(textPlaintext(), change.value());
        assertEquals(1, catalogCalls[0]); // catalog fetched once + cached
        client.handleWebhook(body, h);
        assertEquals(1, catalogCalls[0]);
    }

    @Test
    void accountKeyLoadedOnceAndReused(@TempDir Path tmp) throws Exception {
        KeyPair account = TestCrypto.generateRsa2048();
        Path accountPem = tmp.resolve("account.pem");
        Files.writeString(accountPem, TestCrypto.encryptedPkcs8Pem(account.getPrivate(), "acctpp"),
            StandardCharsets.US_ASCII);
        Path servicePem = tmp.resolve("service-key.pem");
        Files.writeString(servicePem, (String) vector.get("encrypted_private_key_pem"), StandardCharsets.US_ASCII);
        Config cfg = Config.builder()
            .apiUrl("https://api.allme.fyi").clientId("svc").clientSecret("s")
            .servicePrivateKey(servicePem.toString()).keyPassphrase((String) vector.get("passphrase"))
            .accountPrivateKey(accountPem.toString()).accountPassphrase("acctpp")
            .cacheDir(tmp.resolve("c").toString()).webhooks(Map.of(WEBHOOK_ID, SECRET))
            .build();

        fyi.allme.allus.companydata.internal.Transport catalogOnly =
            new fyi.allme.allus.companydata.internal.Transport() {
                @Override
                public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
                    return FakeTransport.tokenOk();
                }

                @Override
                public Response get(String url, Map<String, String> params, Map<String, String> headers) {
                    return FakeTransport.json(200, "{\"request_fields\":[{\"slug\":\"work_email\","
                        + "\"label\":\"Work email\",\"type\":\"email\",\"one_time\":false,"
                        + "\"mandatory_provide\":true,\"mandatory_connected\":false}]}");
                }
            };

        // The Client loads the account key ONCE at construction (no per-webhook PBKDF2).
        Client client = new Client(cfg, catalogOnly);
        Map<String, Object> envelope = TestCrypto.wrapToAccountKey(
            (java.security.interfaces.RSAPublicKey) account.getPublic(), changeBody());
        byte[] body = fyi.allme.allus.companydata.internal.Json.write(envelope).getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = headers(body);

        for (int i = 0; i < 3; i++) {
            Change change = client.handleWebhook(body, h);
            assertEquals("chg-1", change.id());
            assertEquals(textPlaintext(), change.value());
        }
    }

    @Test
    void parseWebhookLoadsAccountKeyWhenNotSupplied(@TempDir Path tmp) throws Exception {
        KeyPair account = TestCrypto.generateRsa2048();
        Path accountPem = tmp.resolve("account.pem");
        Files.writeString(accountPem, TestCrypto.encryptedPkcs8Pem(account.getPrivate(), "acctpp"),
            StandardCharsets.US_ASCII);
        Path servicePem = tmp.resolve("service-key.pem");
        Files.writeString(servicePem, (String) vector.get("encrypted_private_key_pem"), StandardCharsets.US_ASCII);
        Config cfg = Config.builder()
            .apiUrl("https://api.allme.fyi").clientId("svc").clientSecret("s")
            .servicePrivateKey(servicePem.toString()).keyPassphrase((String) vector.get("passphrase"))
            .accountPrivateKey(accountPem.toString()).accountPassphrase("acctpp")
            .cacheDir(tmp.resolve("c").toString()).webhooks(Map.of(WEBHOOK_ID, SECRET))
            .build();

        Map<String, Object> envelope = TestCrypto.wrapToAccountKey(
            (java.security.interfaces.RSAPublicKey) account.getPublic(), changeBody());
        byte[] body = fyi.allme.allus.companydata.internal.Json.write(envelope).getBytes(StandardCharsets.UTF_8);

        // No accountKey kwarg → loaded from config on demand.
        Change change = Webhooks.parseWebhook(body, headers(body), cfg, deps(), null);
        assertEquals("chg-1", change.id());
        assertEquals(textPlaintext(), change.value());
    }
}
