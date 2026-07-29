package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Http;
import fyi.allme.allus.companydata.internal.Json;
import fyi.allme.allus.companydata.internal.Transport;
import fyi.allme.allus.companydata.internal.Transport.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CustomerClient (b2b) — parse + method-shape + key-sourcing tests.
 * Reuses the shared decryption vector's key as the customer ACCOUNT key.
 */
final class CustomerClientTest {

    private static Map<String, Object> vector() {
        return TestData.vector();
    }

    private interface WriteRouter {
        Response apply(String method, String url, byte[] body);
    }

    private static final class RoutingTransport implements Transport {
        final BiFunction<String, Map<String, String>, Response> router;
        final WriteRouter writeRouter;
        byte[] lastBody;
        String lastUrl;

        RoutingTransport(BiFunction<String, Map<String, String>, Response> router, WriteRouter writeRouter) {
            this.router = router;
            this.writeRouter = writeRouter;
        }

        public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
            return FakeTransport.tokenOk();
        }

        public Response get(String url, Map<String, String> params, Map<String, String> headers) {
            return router.apply(url, params);
        }

        public Response send(String method, String url, byte[] body, Map<String, String> headers) {
            lastUrl = url;
            lastBody = body;
            if (writeRouter != null) {
                return writeRouter.apply(method, url, body);
            }
            return FakeTransport.json(200, "{}");
        }
    }

    private static Config customerConfig(Path tmp) throws Exception {
        Map<String, Object> v = vector();
        Path pem = tmp.resolve("account-key.pem");
        Files.writeString(pem, (String) v.get("encrypted_private_key_pem"), StandardCharsets.US_ASCII);
        Path cfg = tmp.resolve("customer.json");
        Files.writeString(cfg, Json.write(Map.of(
            "api_url", "https://api.allme.fyi",
            "customer_client_id", "acct_abc",
            "customer_client_secret", "topsecret",
            "account_private_key", pem.toString(),
            "account_passphrase", v.get("passphrase"),
            "cache_dir", tmp.resolve("cache").toString())));
        return Config.fromCustomerFile(cfg.toString());
    }

    private static CustomerClient customer(Config config, RoutingTransport t) {
        return new CustomerClient(config, new Http(config, t));
    }

    @Test
    void configRequiresAcctPair(@TempDir Path tmp) throws Exception {
        Path cfg = tmp.resolve("c.json");
        Files.writeString(cfg, "{\"api_url\":\"https://api.allme.fyi\"}");
        assertThrows(ConfigException.class, () -> Config.fromCustomerFile(cfg.toString()));
    }

    @Test
    void connectionsParse(@TempDir Path tmp) throws Exception {
        Config config = customerConfig(tmp);
        String body = "{\"connections\":[{\"id\":\"conn-1\",\"customer_type\":\"company\","
            + "\"company\":{\"user_id\":\"co-1\",\"display_name\":\"Acme BV\",\"share_code\":\"ACME01\"},"
            + "\"company_profile\":[{\"slug\":\"company_email\",\"value\":\"hi@acme.example\"}],"
            + "\"services\":[{\"service_link_id\":\"sl-1\",\"service_name\":\"CRM\",\"shared\":[{\"slug\":\"x\",\"value\":\"y\"}]}]}]}";
        RoutingTransport t = new RoutingTransport((url, params) -> FakeTransport.json(200, body), null);
        CustomerClient c = customer(config, t);
        List<CustomerConnection> conns = c.connections();
        assertEquals(1, conns.size());
        assertEquals("company", conns.get(0).customerType());
        assertEquals("Acme BV", conns.get(0).companyName());
        assertEquals("ACME01", conns.get(0).companyCode());
        assertEquals("CRM", conns.get(0).services().get(0).serviceName());
    }

    @Test
    void provideConsentEncryptsToServiceKey(@TempDir Path tmp) throws Exception {
        Config config = customerConfig(tmp);
        RSAPrivateKey priv = Crypto.loadPrivateKey(((String) vector().get("encrypted_private_key_pem")).getBytes(StandardCharsets.US_ASCII), (String) vector().get("passphrase"));
        String spki = TestCrypto.spkiB64(TestCrypto.publicKeyOf(priv));
        RoutingTransport t = new RoutingTransport(
            (url, params) -> url.contains("/api/keys/ACME01/CRM")
                ? FakeTransport.json(200, "{\"public_key\":\"" + spki + "\"}")
                : FakeTransport.json(200, "{}"),
            (method, url, b) -> FakeTransport.json(200, "{\"ok\":true}"));
        CustomerClient c = customer(config, t);
        c.provideConsent("consent-1", List.of(new CustomerClient.TypedAnswer("rf-1", "billing@me.example")), "ACME01", "CRM");
        assertTrue(t.lastUrl.endsWith("/consents/consent-1/provide"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = Json.parseObject(new String(t.lastBody, StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        List<Object> decisions = (List<Object>) sent.get("decisions");
        @SuppressWarnings("unchecked")
        Map<String, Object> dec = (Map<String, Object>) decisions.get(0);
        assertEquals("typed", dec.get("kind"));
        String plain = Crypto.decrypt(Wrapper.of(dec.get("value")), priv);
        assertEquals("billing@me.example", plain);
    }

    @Test
    void documentFileDecryptsWithAccountKey(@TempDir Path tmp) throws Exception {
        Config config = customerConfig(tmp);
        RSAPrivateKey priv = Crypto.loadPrivateKey(((String) vector().get("encrypted_private_key_pem")).getBytes(StandardCharsets.US_ASCII), (String) vector().get("passphrase"));
        RSAPublicKey pub = TestCrypto.publicKeyOf(priv);
        Map<String, Object> wrapper = TestCrypto.encryptForKey(pub, "{\"file\":\"data:application/pdf;base64,AAA\",\"name\":\"contract.pdf\"}");
        String body = Json.write(Map.of("encrypted", true, "value", wrapper));
        RoutingTransport t = new RoutingTransport((url, params) -> FakeTransport.json(200, body), null);
        CustomerClient c = customer(config, t);
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) c.documentFile("conn-1", "doc-1");
        assertEquals("contract.pdf", out.get("name"));
    }

    @Test
    void drainBatchUsesCustomerChanges(@TempDir Path tmp) throws Exception {
        Config config = customerConfig(tmp);
        boolean[] hit = {false};
        RoutingTransport t = new RoutingTransport((url, params) -> {
            if (url.contains("/api/customer/changes")) {
                hit[0] = true;
                return FakeTransport.json(200, "{\"changes\":[{\"id\":\"ch-1\",\"event\":\"share_changed\",\"customer_type\":\"company\"}]}");
            }
            return FakeTransport.json(200, "{}");
        }, null);
        CustomerClient c = customer(config, t);
        List<Change> changes = c.drainBatch(10);
        assertTrue(hit[0]);
        assertEquals("ch-1", changes.get(0).id());
        assertEquals("company", changes.get(0).customerType());
    }

    @Test
    void noSignOrAcceptMethods() {
        for (String banned : List.of("sign", "accept", "signDocument", "acceptDocument", "signEmailCode")) {
            boolean present = false;
            for (var m : CustomerClient.class.getMethods()) {
                if (m.getName().equals(banned)) {
                    present = true;
                }
            }
            assertFalse(present, "CustomerClient must not expose " + banned + " (D6)");
        }
        assertNotNull(CustomerClient.class);
    }
}
