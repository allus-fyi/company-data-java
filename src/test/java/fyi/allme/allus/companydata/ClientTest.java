package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Transport;
import fyi.allme.allus.companydata.internal.Transport.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Client-facade tests. Mirrors the Python reference's test_client. */
class ClientTest {
    private static Map<String, Object> vector;
    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;

    @BeforeAll
    static void setUp() {
        vector = TestData.vector();
        privateKey = Crypto.loadPrivateKey(
            ((String) vector.get("encrypted_private_key_pem")).getBytes(StandardCharsets.UTF_8),
            (String) vector.get("passphrase"));
        publicKey = TestCrypto.publicKeyOf(privateKey);
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

    private static Path pemPath(Path tmp) throws Exception {
        Path p = tmp.resolve("service-key.pem");
        Files.writeString(p, (String) vector.get("encrypted_private_key_pem"), StandardCharsets.US_ASCII);
        return p;
    }

    private static Config config(Path tmp) throws Exception {
        return Config.builder()
            .apiUrl("https://api.allme.fyi").clientId("svc_abc").clientSecret("topsecret")
            .servicePrivateKey(pemPath(tmp).toString()).keyPassphrase((String) vector.get("passphrase"))
            .cacheDir(tmp.resolve("cache").toString())
            .build();
    }

    /**
     * A Transport that routes GETs through a function and POST/PUT/DELETE bodies through
     * an optional write router; the OAuth token POST (form) always returns the token.
     */
    static final class RoutingTransport implements Transport {
        final BiFunction<String, Map<String, String>, Response> router;
        final WriteRouter writeRouter;
        final List<GetCall> gets = new ArrayList<>();
        final List<WriteCall> writes = new ArrayList<>();

        record GetCall(String url, Map<String, String> params) {
        }

        /** A recorded write: method, url, and the body parsed back to a Map (json) or raw bytes. */
        record WriteCall(String method, String url, Object jsonBody, byte[] data) {
        }

        interface WriteRouter {
            Response apply(String method, String url, Object jsonBody, byte[] data);
        }

        RoutingTransport(BiFunction<String, Map<String, String>, Response> router) {
            this(router, null);
        }

        RoutingTransport(BiFunction<String, Map<String, String>, Response> router, WriteRouter writeRouter) {
            this.router = router;
            this.writeRouter = writeRouter;
        }

        @Override
        public Response postForm(String url, Map<String, String> form, Map<String, String> headers) {
            return FakeTransport.tokenOk();
        }

        @Override
        public Response get(String url, Map<String, String> params, Map<String, String> headers) {
            gets.add(new GetCall(url, params));
            return router.apply(url, params);
        }

        @Override
        public Response send(String method, String url, byte[] body, Map<String, String> headers) {
            // The Http façade serializes JSON bodies; reflect that back to the router as
            // a parsed Map when the content type was JSON, AND always expose the raw bytes
            // (a per-person file upload is a JSON wrapper the test inspects as bytes).
            Object jsonBody = null;
            boolean isJson = headers != null && "application/json".equals(headers.get("Content-Type"));
            if (body != null && isJson) {
                try {
                    jsonBody = fyi.allme.allus.companydata.internal.Json.parse(
                        new String(body, java.nio.charset.StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                    jsonBody = null;
                }
            }
            writes.add(new WriteCall(method, url, jsonBody, body));
            if (writeRouter == null) {
                return FakeTransport.json(200, "{}");
            }
            return writeRouter.apply(method, url, jsonBody, body);
        }
    }

    private static final String REQUEST_FIELDS_BODY =
        "{\"request_fields\":["
        + "{\"slug\":\"work_email\",\"label\":\"Work email\",\"type\":\"email\","
        + "\"one_time\":false,\"mandatory_provide\":true,\"mandatory_connected\":false},"
        + "{\"slug\":\"billing_address\",\"label\":\"Billing address\",\"type\":\"address\","
        + "\"one_time\":false,\"mandatory_provide\":false,\"mandatory_connected\":false},"
        + "{\"slug\":\"logo\",\"label\":\"Logo\",\"type\":\"photo\","
        + "\"one_time\":true,\"mandatory_provide\":false,\"mandatory_connected\":false}]}";

    private static Map<String, Object> encryptForKey(String plaintext) {
        return TestCrypto.encryptForKey(publicKey, plaintext);
    }

    private static String sha256(byte[] b) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── request_fields() caches ────────────────────────────────────────────────

    @Test
    void requestFieldsParsedAndCached(@TempDir Path tmp) throws Exception {
        int[] calls = {0};
        RoutingTransport t = new RoutingTransport((url, params) -> {
            if (url.endsWith("/request-fields")) {
                calls[0]++;
                return FakeTransport.json(200, REQUEST_FIELDS_BODY);
            }
            throw new AssertionError("unexpected GET " + url);
        });
        Client client = new Client(config(tmp), t);
        List<RequestField> fields = client.requestFields();
        assertEquals(List.of("work_email", "billing_address", "logo"),
            fields.stream().map(RequestField::slug).toList());
        assertTrue(fields.get(0).mandatory());

        client.requestFields(); // cached
        assertEquals(1, calls[0]);
    }

    // ── connections() lazy iterable with decrypted values ───────────────────────

    @Test
    void connectionsYieldsTypedDecrypted(@TempDir Path tmp) throws Exception {
        Map<String, Object> addrWrapper = encryptForKey("{\"city\":\"Utrecht\",\"country\":\"NL\"}");
        String page1 = fyi.allme.allus.companydata.internal.Json.write(Map.of(
            "total", 2,
            "items", List.of(Map.of(
                "connection_id", "csc-1",
                "user_id", "person-1",
                "display_name", "Anna",
                "connected_at", "2026-06-10T00:00:00Z",
                "values", Map.of(
                    "work_email", Map.of("value", textWrapper(), "live", true, "updatedAt", "2026-06-17T10:00:00Z"),
                    "billing_address", Map.of("value", addrWrapper, "live", false),
                    "logo", Map.of("value_url",
                        "https://api.allme.fyi/api/company-data/connections/csc-1/slots/sf-9/file", "live", true)),
                "pending_consent", List.of()))));

        RoutingTransport t = new RoutingTransport((url, params) -> {
            if (url.endsWith("/request-fields")) {
                return FakeTransport.json(200, REQUEST_FIELDS_BODY);
            }
            if (url.endsWith("/connections")) {
                return FakeTransport.json(200, page1); // short page (1 < limit) → stop
            }
            throw new AssertionError("unexpected GET " + url);
        });
        Client client = new Client(config(tmp), t);
        List<Connection> conns = new ArrayList<>();
        for (Connection c : client.connections(100, 0)) {
            conns.add(c);
        }
        assertEquals(1, conns.size());
        Connection conn = conns.get(0);
        assertEquals("csc-1", conn.id());
        assertEquals("person-1", conn.personId());
        assertEquals("Anna", conn.displayName());

        assertEquals(textPlaintext(), conn.values().get("work_email").value());
        assertTrue(conn.values().get("work_email").live());
        assertEquals(Map.of("city", "Utrecht", "country", "NL"), conn.values().get("billing_address").value());
        assertInstanceOf(BinaryHandle.class, conn.values().get("logo").value());

        long connGets = t.gets.stream().filter(g -> g.url().endsWith("/connections")).count();
        assertEquals(1, connGets);
        assertFalse(t.gets.stream().anyMatch(g -> g.url().contains("/file")));
    }

    @Test
    void connectionsAutoPages(@TempDir Path tmp) throws Exception {
        List<String> pages = List.of(
            fyi.allme.allus.companydata.internal.Json.write(Map.of("total", 3, "items", List.of(
                Map.of("connection_id", "c1", "user_id", "p1", "display_name", "N1", "values", Map.of()),
                Map.of("connection_id", "c2", "user_id", "p2", "display_name", "N2", "values", Map.of())))),
            fyi.allme.allus.companydata.internal.Json.write(Map.of("total", 3, "items", List.of(
                Map.of("connection_id", "c3", "user_id", "p3", "display_name", "N3", "values", Map.of())))));
        int[] i = {0};
        RoutingTransport t = new RoutingTransport((url, params) -> {
            if (url.endsWith("/request-fields")) {
                return FakeTransport.json(200, "{\"request_fields\":[]}");
            }
            if (url.endsWith("/connections")) {
                return FakeTransport.json(200, pages.get(i[0]++));
            }
            throw new AssertionError("unexpected GET " + url);
        });
        Client client = new Client(config(tmp), t);
        List<String> ids = new ArrayList<>();
        for (Connection c : client.connections(2, 0)) {
            ids.add(c.id());
        }
        assertEquals(List.of("c1", "c2", "c3"), ids);
        List<String> offsets = t.gets.stream().filter(g -> g.url().endsWith("/connections"))
            .map(g -> g.params().get("offset")).toList();
        assertEquals(List.of("0", "2"), offsets);
    }

    // ── binary handle fetches the slot endpoint + decrypts ──────────────────────

    @Test
    void binaryHandleFetchesSlotAndDecrypts(@TempDir Path tmp) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> binary = (Map<String, Object>) vector.get("binary");
        String page = fyi.allme.allus.companydata.internal.Json.write(Map.of(
            "total", 1, "items", List.of(Map.of(
                "connection_id", "csc-1", "user_id", "person-1", "display_name", "Anna",
                "values", Map.of("logo", Map.of("value_url",
                    "https://api.allme.fyi/api/company-data/connections/csc-1/slots/sf-9/file", "live", true))))));
        RoutingTransport t = new RoutingTransport((url, params) -> {
            if (url.endsWith("/request-fields")) {
                return FakeTransport.json(200, REQUEST_FIELDS_BODY);
            }
            if (url.endsWith("/connections")) {
                return FakeTransport.json(200, page);
            }
            if (url.endsWith("/slots/sf-9/file")) {
                return FakeTransport.json(200, fyi.allme.allus.companydata.internal.Json.write(
                    Map.of("encrypted", true, "value", binary.get("wrapper"))));
            }
            throw new AssertionError("unexpected GET " + url);
        });
        Client client = new Client(config(tmp), t);
        Connection conn = client.connections().iterator().next();
        BinaryHandle handle = (BinaryHandle) conn.values().get("logo").value();
        assertFalse(t.gets.stream().anyMatch(g -> g.url().contains("/file"))); // lazy

        byte[] data = handle.bytes();
        assertTrue(t.gets.stream().anyMatch(g -> g.url().endsWith("/slots/sf-9/file")));
        assertEquals(binary.get("inner_full_sha256"), sha256(data));
    }

    // ── connection(id) ──────────────────────────────────────────────────────────

    @Test
    void connectionById(@TempDir Path tmp) throws Exception {
        String detail = fyi.allme.allus.companydata.internal.Json.write(Map.of(
            "connection_id", "csc-7", "user_id", "person-7",
            "values", Map.of("work_email", Map.of("value", textWrapper(), "live", true))));
        RoutingTransport t = new RoutingTransport((url, params) -> {
            if (url.endsWith("/request-fields")) {
                return FakeTransport.json(200, REQUEST_FIELDS_BODY);
            }
            if (url.endsWith("/connections/csc-7")) {
                return FakeTransport.json(200, detail);
            }
            throw new AssertionError("unexpected GET " + url);
        });
        Client client = new Client(config(tmp), t);
        Connection conn = client.connection("csc-7");
        assertEquals("csc-7", conn.id());
        assertEquals("person-7", conn.personId());
        assertEquals(textPlaintext(), conn.values().get("work_email").value());
    }

    // ── logs() ──────────────────────────────────────────────────────────────────

    @Test
    void logsDeserialize(@TempDir Path tmp) throws Exception {
        String body = fyi.allme.allus.companydata.internal.Json.write(Map.of(
            "total", 2, "items", List.of(
                Map.of("type", "email", "message", "stale-queue alert", "metadata", Map.of("days", 3),
                    "created_at", "2026-06-17T06:00:00Z"),
                Map.of("type", "purge", "message", "purged 4", "metadata", Map.of("count", 4),
                    "created_at", "2026-06-17T07:00:00Z"))));
        RoutingTransport t = new RoutingTransport((url, params) -> {
            if (url.endsWith("/logs")) {
                return FakeTransport.json(200, body);
            }
            throw new AssertionError("unexpected GET " + url);
        });
        Client client = new Client(config(tmp), t);
        List<LogEntry> logs = client.logs(50, 0);
        assertEquals(2, logs.size());
        assertEquals("email", logs.get(0).type());
        assertEquals(Map.of("days", 3), logs.get(0).metadata());
        assertEquals("50", t.gets.get(0).params().get("limit"));
    }

    // ── process_changes() drains the feed through the pump one-by-one ────────────

    @Test
    void processChangesDrainsThroughPump(@TempDir Path tmp) throws Exception {
        boolean[] served = {false};
        RoutingTransport t = new RoutingTransport((url, params) -> {
            if (url.endsWith("/request-fields")) {
                return FakeTransport.json(200, REQUEST_FIELDS_BODY);
            }
            if (url.endsWith("/changes")) {
                if (served[0]) {
                    return FakeTransport.json(200, "{\"changes\":[]}");
                }
                served[0] = true;
                return FakeTransport.json(200, fyi.allme.allus.companydata.internal.Json.write(Map.of("changes", List.of(
                    Map.of("id", "chg-1", "event", "field_updated", "person_user_id", "person-1",
                        "slug", "work_email", "value", textWrapper(), "live", true, "at", "2026-06-17T12:00:00Z"),
                    Map.of("id", "chg-2", "event", "connection_created", "person_user_id", "person-2",
                        "at", "2026-06-17T12:05:00Z")))));
            }
            throw new AssertionError("unexpected GET " + url);
        });
        Client client = new Client(config(tmp), t);

        List<Object[]> seen = new ArrayList<>();
        client.processChanges(c -> seen.add(new Object[]{c.id(), c.event(), c.value()}));

        assertEquals(List.of("chg-1", "chg-2"), seen.stream().map(s -> (String) s[0]).toList());
        assertEquals("field_updated", seen.get(0)[1]);
        assertEquals(textPlaintext(), seen.get(0)[2]);
        assertEquals("connection_created", seen.get(1)[1]);
        assertEquals(null, seen.get(1)[2]);
        assertTrue(client.pump().buffer().pending().isEmpty());
    }

    // ── construction reads the key once (config-only keys) ─────────

    @Test
    void fromConfigLoadsKey(@TempDir Path tmp) throws Exception {
        Path pem = pemPath(tmp);
        Path cfg = tmp.resolve("config.json");
        Files.writeString(cfg, fyi.allme.allus.companydata.internal.Json.write(Map.of(
            "api_url", "https://api.allme.fyi", "client_id", "svc_abc", "client_secret", "s",
            "service_private_key", pem.toString(), "key_passphrase", vector.get("passphrase"),
            "cache_dir", tmp.resolve("cache").toString())), StandardCharsets.UTF_8);
        // Build via fromConfig (reads the file) but inject a transport so we can prove
        // the loaded service key actually decrypts the vector through the full facade.
        Client client = new Client(Config.fromFile(cfg.toString()), new RoutingTransport((url, params) -> {
            if (url.endsWith("/request-fields")) {
                return FakeTransport.json(200, REQUEST_FIELDS_BODY);
            }
            if (url.endsWith("/connections/csc-1")) {
                return FakeTransport.json(200, fyi.allme.allus.companydata.internal.Json.write(Map.of(
                    "connection_id", "csc-1", "user_id", "p1",
                    "values", Map.of("work_email", Map.of("value", textWrapper(), "live", true)))));
            }
            throw new AssertionError("unexpected GET " + url);
        }));
        assertEquals(textPlaintext(), client.connection("csc-1").values().get("work_email").value());
    }

    @Test
    void fromConfigBadPassphraseIsConfigException(@TempDir Path tmp) throws Exception {
        Path pem = pemPath(tmp);
        Path cfg = tmp.resolve("config.json");
        Files.writeString(cfg, fyi.allme.allus.companydata.internal.Json.write(Map.of(
            "api_url", "https://api.allme.fyi", "client_id", "x", "client_secret", "s",
            "service_private_key", pem.toString(), "key_passphrase", "WRONG",
            "cache_dir", tmp.resolve("cache").toString())), StandardCharsets.UTF_8);
        assertThrows(ConfigException.class, () -> Client.fromConfig(cfg.toString()));
    }

    // ── company documents (write) ───────────────────────────────────────────────

    /** The vector key's PUBLIC half as base64 SPKI/DER (what GET /api/keys returns). */
    private static String vectorPubSpkiB64() {
        return TestCrypto.spkiB64(publicKey);
    }

    private static BiFunction<String, Map<String, String>, Response> noGet() {
        return (url, params) -> {
            throw new AssertionError("unexpected GET " + url);
        };
    }

    @SuppressWarnings("unchecked")
    private static String decryptVectorWrapper(Object wrapper) {
        return Crypto.decrypt(Wrapper.of(wrapper), privateKey);
    }

    @Test
    void createDocumentBroadcastJsonIsPlaintext(@TempDir Path tmp) throws Exception {
        Object[] posted = new Object[1];
        RoutingTransport.WriteRouter wr = (method, url, jsonBody, data) -> {
            assertEquals("POST", method);
            assertTrue(url.endsWith("/documents"));
            posted[0] = jsonBody;
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) jsonBody;
            return FakeTransport.json(201, fyi.allme.allus.companydata.internal.Json.write(Map.of(
                "id", "d1", "kind", "document", "name", "Terms",
                "status", "active", "payload_kind", "json", "is_private", false,
                "value", b.get("value"))));
        };
        RoutingTransport t = new RoutingTransport(noGet(), wr);
        Client client = new Client(config(tmp), t);
        Document doc = client.createDocument(Client.CreateDocumentRequest.builder()
            .name("Terms").payloadKind("json")
            .jsonValue(Map.of("url", "x", "v", "1")).status("active"));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) posted[0];
        assertNull(body.get("target"));
        assertEquals(Map.of("url", "x", "v", "1"), body.get("value")); // plaintext, no _enc
        assertEquals(false, body.get("is_private"));
        assertEquals("d1", doc.id());
        assertEquals("active", doc.status());
    }

    @Test
    void createDocumentPerPersonEncryptsForBothPrivacy(@TempDir Path tmp) throws Exception {
        String spki = vectorPubSpkiB64();
        for (boolean isPrivate : new boolean[]{false, true}) {
            int[] keysFetched = {0};
            BiFunction<String, Map<String, String>, Response> get = (url, params) -> {
                assertTrue(url.endsWith("/api/keys/ABC123"));
                keysFetched[0]++;
                return FakeTransport.json(200, fyi.allme.allus.companydata.internal.Json.write(
                    Map.of("public_key", spki)));
            };
            Object[] captured = new Object[1];
            RoutingTransport.WriteRouter wr = (method, url, jsonBody, data) -> {
                captured[0] = jsonBody;
                @SuppressWarnings("unchecked")
                Map<String, Object> b = (Map<String, Object>) jsonBody;
                return FakeTransport.json(201, fyi.allme.allus.companydata.internal.Json.write(Map.of(
                    "id", "d2", "kind", "document", "name", "PP",
                    "status", "active", "payload_kind", "json", "is_private", isPrivate,
                    "value", b.get("value"))));
            };
            RoutingTransport t = new RoutingTransport(get, wr);
            Client client = new Client(config(tmp), t);
            Document doc = client.createDocument(Client.CreateDocumentRequest.builder()
                .name("PP").payloadKind("json").jsonValue(Map.of("plan", "pro"))
                .connectionId("conn-1").shareCode("ABC123").isPrivate(isPrivate));

            assertEquals(1, keysFetched[0]); // fetched the recipient key
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) captured[0];
            @SuppressWarnings("unchecked")
            Map<String, Object> val = (Map<String, Object>) body.get("value");
            assertEquals(1, ((Number) val.get("_enc")).intValue()); // ENCRYPTED, any is_private
            assertTrue(val.containsKey("k") && val.containsKey("iv") && val.containsKey("d"));
            assertEquals(Map.of("connection_id", "conn-1"), body.get("target"));
            assertEquals(isPrivate, body.get("is_private"));
            // round-trips through the SDK's own decrypt → the original plaintext
            assertEquals(Map.of("plan", "pro"),
                fyi.allme.allus.companydata.internal.Json.parse(decryptVectorWrapper(val)));
            assertEquals("d2", doc.id());
        }
    }

    @Test
    void createDocumentPrivateBroadcastThrows(@TempDir Path tmp) throws Exception {
        RoutingTransport t = new RoutingTransport(noGet(), (m, u, j, d) -> {
            throw new AssertionError("should not POST");
        });
        Client client = new Client(config(tmp), t);
        assertThrows(ConfigException.class, () -> client.createDocument(
            Client.CreateDocumentRequest.builder()
                .name("x").payloadKind("json").jsonValue(Map.of("a", 1)).isPrivate(true)));
    }

    @Test
    void createDocumentFileBroadcastUploadsRawBytes(@TempDir Path tmp) throws Exception {
        List<RoutingTransport.WriteCall> calls = new ArrayList<>();
        RoutingTransport.WriteRouter wr = (method, url, jsonBody, data) -> {
            calls.add(new RoutingTransport.WriteCall(method, url, jsonBody, data));
            if (url.endsWith("/documents")) {
                return FakeTransport.json(201, fyi.allme.allus.companydata.internal.Json.write(Map.of(
                    "id", "f1", "kind", "document", "name", "C",
                    "status", "active", "payload_kind", "file", "is_private", false,
                    "value", Map.of("_pending", true))));
            }
            assertTrue(url.endsWith("/documents/f1/file"));
            return FakeTransport.json(200, "{\"id\":\"f1\"}");
        };
        RoutingTransport t = new RoutingTransport(noGet(), wr);
        Client client = new Client(config(tmp), t);
        client.createDocument(Client.CreateDocumentRequest.builder()
            .name("C").payloadKind("file")
            .fileBytes("%PDF-1.4 x".getBytes(StandardCharsets.UTF_8)).fileMime("application/pdf"));

        assertTrue(calls.get(0).url().endsWith("/documents"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body0 = (Map<String, Object>) calls.get(0).jsonBody();
        assertNull(body0.get("target"));
        assertTrue(calls.get(1).url().endsWith("/documents/f1/file"));
        assertArrayEquals("%PDF-1.4 x".getBytes(StandardCharsets.UTF_8), calls.get(1).data()); // raw bytes
    }

    @Test
    void createDocumentFilePerPersonUploadsWrapperBytes(@TempDir Path tmp) throws Exception {
        String spki = vectorPubSpkiB64();
        List<RoutingTransport.WriteCall> calls = new ArrayList<>();
        BiFunction<String, Map<String, String>, Response> get = (url, params) ->
            FakeTransport.json(200, fyi.allme.allus.companydata.internal.Json.write(Map.of("public_key", spki)));
        RoutingTransport.WriteRouter wr = (method, url, jsonBody, data) -> {
            calls.add(new RoutingTransport.WriteCall(method, url, jsonBody, data));
            if (url.endsWith("/documents")) {
                return FakeTransport.json(201, fyi.allme.allus.companydata.internal.Json.write(Map.of(
                    "id", "f2", "kind", "document", "name", "C",
                    "status", "active", "payload_kind", "file", "is_private", true,
                    "value", Map.of("_pending", true))));
            }
            return FakeTransport.json(200, "{\"id\":\"f2\"}");
        };
        RoutingTransport t = new RoutingTransport(get, wr);
        Client client = new Client(config(tmp), t);
        client.createDocument(Client.CreateDocumentRequest.builder()
            .name("C").payloadKind("file").fileBytes("hello-bytes".getBytes(StandardCharsets.UTF_8))
            .fileMime("application/pdf").personUserId("u1").shareCode("ABC123").isPrivate(true));

        byte[] upload = calls.get(1).data();
        assertTrue(upload != null && upload.length > 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> wrapper = (Map<String, Object>) fyi.allme.allus.companydata.internal.Json.parse(
            new String(upload, StandardCharsets.UTF_8));
        assertEquals(1, ((Number) wrapper.get("_enc")).intValue()); // ciphertext wrapper bytes, not raw file
        // decrypt → the {"file":"data:...base64,..."} envelope holding the original bytes
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) fyi.allme.allus.companydata.internal.Json.parse(
            decryptVectorWrapper(wrapper));
        String file = (String) env.get("file");
        assertTrue(file.startsWith("data:application/pdf;base64,"));
        assertArrayEquals("hello-bytes".getBytes(StandardCharsets.UTF_8),
            java.util.Base64.getDecoder().decode(file.split(",", 2)[1]));
    }

    @Test
    void documentVerbsHitRightPath(@TempDir Path tmp) throws Exception {
        List<Object[]> seen = new ArrayList<>();
        BiFunction<String, Map<String, String>, Response> get = (url, params) -> {
            if (url.endsWith("/documents")) {
                return FakeTransport.json(200, "{\"total\":0,\"items\":[]}");
            }
            if (url.contains("/documents/d9")) {
                return FakeTransport.json(200,
                    "{\"id\":\"d9\",\"payload_kind\":\"json\",\"is_private\":false,\"value\":{\"a\":1}}");
            }
            throw new AssertionError("unexpected GET " + url);
        };
        RoutingTransport.WriteRouter wr = (method, url, jsonBody, data) -> {
            seen.add(new Object[]{method, url, jsonBody});
            return FakeTransport.json(200,
                "{\"id\":\"d9\",\"payload_kind\":\"json\",\"is_private\":false,\"value\":{\"a\":1},\"status\":\"ended\"}");
        };
        RoutingTransport t = new RoutingTransport(get, wr);
        Client client = new Client(config(tmp), t);

        assertTrue(client.listDocuments(null, "active", 100, 0).isEmpty());
        assertEquals("d9", client.document("d9").id());
        client.updateDocumentStatus("d9", "ended");
        client.updateDocumentMetadata("d9", null, "renamed", null);
        client.deleteDocument("d9");

        List<String[]> methods = seen.stream()
            .map(s -> new String[]{(String) s[0], ((String) s[1]).split("/api/company-data")[1]})
            .toList();
        long puts = methods.stream().filter(m -> m[0].equals("PUT") && m[1].equals("/documents/d9")).count();
        assertEquals(2, puts);
        assertTrue(methods.stream().anyMatch(m -> m[0].equals("DELETE") && m[1].equals("/documents/d9")));
    }
}
