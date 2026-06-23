package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;
import fyi.allme.allus.companydata.internal.Transport.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Company-side contract-flow run methods — fully mocked (no live API). Mirrors the Python/TS/Go/C#
 * run-method tests: trigger/list/get, decrypt-only-company, per-party fan-out + local routing,
 * generate one-time-key shape, and the processFlowRun company-leaf document chain.
 */
class FlowRunTest {
    private static final String COMPANY_UID = "company-1";
    private static final String PERSON_UID = "person-1";

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

    private static Config config(Path tmp) throws Exception {
        Path pem = tmp.resolve("service-key.pem");
        Files.writeString(pem, (String) vector.get("encrypted_private_key_pem"), StandardCharsets.US_ASCII);
        return Config.builder()
            .apiUrl("https://api.allme.fyi").clientId("svc_abc").clientSecret("topsecret")
            .servicePrivateKey(pem.toString()).keyPassphrase((String) vector.get("passphrase"))
            .cacheDir(tmp.resolve("cache").toString())
            .build();
    }

    private static final String FLOW_DEF = """
        {
          "output_mode": "data_only",
          "parties": [{"key":"company"},{"key":"person"}],
          "nodes": [
            {"key":"n1","party":"company"},
            {"key":"n2","party":"person"},
            {"key":"n_end","party":"person"}
          ],
          "edges": [
            {"from":"n1","to":"n_end","sort":0,"condition":{"field":"tier","op":"eq","value":"vip"}},
            {"from":"n1","to":"n2","sort":1,"condition":null}
          ]
        }
        """;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> runObj(String status, String current, Object answers,
            String defJson, String outputMode, String documentId) {
        try {
            Map<String, Object> def = (Map<String, Object>) Json.parse(defJson != null ? defJson : FLOW_DEF);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", "run-1");
            m.put("flow_id", "flow-1");
            m.put("flow_version", 3);
            m.put("service_id", "svc-1");
            m.put("connection_id", "csc-1");
            m.put("company_user_id", COMPANY_UID);
            m.put("bindings", Map.of("company", COMPANY_UID, "person", PERSON_UID));
            m.put("status", status);
            m.put("current_node", current);
            m.put("document_id", documentId);
            m.put("output_mode", outputMode);
            m.put("definition", def);
            m.put("answers", answers != null ? answers : List.of());
            m.put("created_at", null);
            m.put("updated_at", null);
            return m;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Object> runObj() {
        return runObj("awaiting_company", "n1", null, null, "data_only", null);
    }

    private static String json(Map<String, Object> o) {
        return Json.write(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> wrapper(String plaintext) {
        return TestCrypto.encryptForKey(publicKey, plaintext);
    }

    private static boolean isEncWrapper(Object o) {
        if (o instanceof Map<?, ?> m) {
            Object enc = m.get("_enc");
            return (enc instanceof Number n && n.intValue() == 1) || "1".equals(String.valueOf(enc));
        }
        return false;
    }

    private static BiFunction<String, Map<String, String>, Response> noGet() {
        return (url, params) -> {
            throw new AssertionError("unexpected GET " + url);
        };
    }

    private static BiFunction<String, Map<String, String>, Response> keyGet(String spki) {
        return (url, params) -> {
            if (url.endsWith("/company-data/connections/csc-1")) {
                return FakeTransport.json(200, "{\"connection_id\":\"csc-1\",\"share_code\":\"ABC123\"}");
            }
            if (url.endsWith("/api/keys/ABC123")) {
                return FakeTransport.json(200, "{\"public_key\":\"" + spki + "\"}");
            }
            throw new AssertionError("unexpected GET " + url);
        };
    }

    // ── trigger / list / get ──────────────────────────────────────────────────────

    @Test
    void triggerFlowRun(@TempDir Path tmp) throws Exception {
        Object[] captured = new Object[2];
        ClientTest.RoutingTransport t = new ClientTest.RoutingTransport(noGet(),
            (method, url, jsonBody, data) -> {
                captured[0] = url;
                captured[1] = jsonBody;
                return FakeTransport.json(201, json(runObj()));
            });
        Client client = new Client(config(tmp), t);
        FlowRun run = client.triggerFlowRun("flow-1", "csc-1",
            Map.of("company", COMPANY_UID, "person", PERSON_UID));
        assertTrue(((String) captured[0]).endsWith("/company-data/flows/flow-1/runs"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) captured[1];
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) body.get("target");
        assertEquals("csc-1", target.get("connection_id"));
        assertEquals("company", run.companyPartyKey());
        assertEquals(COMPANY_UID, run.serviceUserId());
    }

    @Test
    void flowRunsDefaultAwaitingCompany(@TempDir Path tmp) throws Exception {
        ClientTest.RoutingTransport t = new ClientTest.RoutingTransport((url, params) -> {
            assertTrue(url.endsWith("/company-data/flow-runs"));
            assertEquals("awaiting_company", params.get("status"));
            return FakeTransport.json(200,
                "{\"total\":1,\"items\":[" + json(runObj()) + "]}");
        });
        Client client = new Client(config(tmp), t);
        List<FlowRun> runs = client.flowRuns();
        assertEquals(1, runs.size());
        assertEquals("awaiting_company", runs.get(0).status());
    }

    @Test
    void flowRunById(@TempDir Path tmp) throws Exception {
        ClientTest.RoutingTransport t = new ClientTest.RoutingTransport((url, params) -> {
            assertTrue(url.endsWith("/company-data/flow-runs/run-1"));
            return FakeTransport.json(200, json(runObj()));
        });
        Client client = new Client(config(tmp), t);
        assertEquals("n1", client.flowRun("run-1").currentNode());
    }

    // ── submit: per-party fan-out + local routing ─────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void submitFansOutAndRoutesFallthrough(@TempDir Path tmp) throws Exception {
        String spki = TestCrypto.spkiB64(publicKey);
        Object[] captured = new Object[2];
        ClientTest.RoutingTransport t = new ClientTest.RoutingTransport(keyGet(spki),
            (method, url, jsonBody, data) -> {
                captured[0] = url;
                captured[1] = jsonBody;
                return FakeTransport.json(200, json(runObj("awaiting_person", "n2", null, null, "data_only", null)));
            });
        Client client = new Client(config(tmp), t);
        FlowRun run = FlowRun.fromApi(runObj());
        FlowRun out = client.submitFlowAnswers(run, Map.of("company_name", "ACME BV"));

        assertTrue(((String) captured[0]).endsWith("/company-data/flow-runs/run-1/answers"));
        Map<String, Object> body = (Map<String, Object>) captured[1];
        List<Object> answers = (List<Object>) body.get("answers");
        assertEquals(1, answers.size());
        List<Object> values = (List<Object>) ((Map<String, Object>) answers.get(0)).get("values");
        Set<String> forUsers = values.stream()
            .map(v -> String.valueOf(((Map<String, Object>) v).get("for_user_id")))
            .collect(Collectors.toSet());
        assertEquals(Set.of(COMPANY_UID, PERSON_UID), forUsers);
        for (Object v : values) {
            assertTrue(isEncWrapper(((Map<String, Object>) v).get("value")));
        }
        // company copy round-trips with the service private key
        Object companyVal = values.stream()
            .filter(v -> COMPANY_UID.equals(((Map<String, Object>) v).get("for_user_id")))
            .map(v -> ((Map<String, Object>) v).get("value")).findFirst().orElseThrow();
        assertEquals("ACME BV", Crypto.decrypt(Wrapper.of(companyVal), privateKey));
        // local routing: no 'tier' → fallthrough to n2
        assertEquals("n2", body.get("next_node"));
        assertEquals("person", body.get("next_party"));
        assertFalse(body.containsKey("leaf"));
        assertEquals("awaiting_person", out.status());
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitRoutesGuardedEdge(@TempDir Path tmp) throws Exception {
        String spki = TestCrypto.spkiB64(publicKey);
        Object[] captured = new Object[1];
        ClientTest.RoutingTransport t = new ClientTest.RoutingTransport(keyGet(spki),
            (method, url, jsonBody, data) -> {
                captured[0] = jsonBody;
                return FakeTransport.json(200, json(runObj("awaiting_person", "n_end", null, null, "data_only", null)));
            });
        Client client = new Client(config(tmp), t);
        FlowRun run = FlowRun.fromApi(runObj());
        client.submitFlowAnswers(run, Map.of("tier", "vip"));
        Map<String, Object> body = (Map<String, Object>) captured[0];
        assertEquals("n_end", body.get("next_node"));
        assertFalse(body.containsKey("leaf"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitUsesSuppliedPartyPubKeys(@TempDir Path tmp) throws Exception {
        Object[] captured = new Object[1];
        ClientTest.RoutingTransport t = new ClientTest.RoutingTransport(noGet(),
            (method, url, jsonBody, data) -> {
                captured[0] = jsonBody;
                return FakeTransport.json(200, json(runObj("awaiting_person", "n2", null, null, "data_only", null)));
            });
        Client client = new Client(config(tmp), t);
        FlowRun run = FlowRun.fromApi(runObj());
        client.submitFlowAnswers(run, Map.of("company_name", "X"), Map.of(PERSON_UID, publicKey));
        Map<String, Object> body = (Map<String, Object>) captured[0];
        List<Object> values = (List<Object>) ((Map<String, Object>) ((List<Object>) body.get("answers")).get(0)).get("values");
        assertEquals(2, values.size());
    }

    // ── generate (document leaf) ──────────────────────────────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void generateFlowDocument(@TempDir Path tmp) throws Exception {
        Object answers = List.of(Map.of("slug", "company_name", "for_user_id", COMPANY_UID, "value", wrapper("ACME BV")));
        Object[] captured = new Object[2];
        ClientTest.RoutingTransport t = new ClientTest.RoutingTransport(noGet(),
            (method, url, jsonBody, data) -> {
                captured[0] = url;
                captured[1] = jsonBody;
                return FakeTransport.json(200, "{\"document_id\":\"doc-9\",\"status\":\"awaiting_signature\"}");
            });
        Client client = new Client(config(tmp), t);
        FlowRun run = FlowRun.fromApi(runObj("generating", "n1", answers, null, "document", null));
        Object res = client.generateFlowDocument(run);
        assertEquals("doc-9", ((Map<String, Object>) res).get("document_id"));
        assertTrue(((String) captured[0]).endsWith("/company-data/flow-runs/run-1/generate"));

        Map<String, Object> body = (Map<String, Object>) captured[1];
        byte[] otk = java.util.Base64.getDecoder().decode((String) body.get("otk"));
        byte[] blob = java.util.Base64.getDecoder().decode((String) body.get("values"));
        assertEquals(32, otk.length);
        assertTrue(blob.length >= 12 + 16);
        // reproduce the server read: iv(12) || ct || tag(16)
        byte[] iv = java.util.Arrays.copyOfRange(blob, 0, 12);
        byte[] ctWithTag = java.util.Arrays.copyOfRange(blob, 12, blob.length);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
            new javax.crypto.spec.SecretKeySpec(otk, "AES"),
            new javax.crypto.spec.GCMParameterSpec(128, iv));
        String plain = new String(cipher.doFinal(ctWithTag), StandardCharsets.UTF_8);
        Map<String, Object> got = (Map<String, Object>) Json.parse(plain);
        assertEquals("ACME BV", got.get("company_name"));
    }

    // ── processFlowRun: chains submit + generate on a company-leaf document flow ───

    @Test
    void processFlowRunCompanyLeafDocument(@TempDir Path tmp) throws Exception {
        String spki = TestCrypto.spkiB64(publicKey);
        String single = """
            {"output_mode":"document","parties":[{"key":"company"},{"key":"person"}],
             "nodes":[{"key":"n1","party":"company"}],"edges":[]}
            """;
        List<String> posts = new java.util.ArrayList<>();
        ClientTest.RoutingTransport t = new ClientTest.RoutingTransport(
            (url, params) -> {
                if (url.endsWith("/company-data/flow-runs/run-1")) {
                    String status = posts.isEmpty() ? "awaiting_company" : "awaiting_signature";
                    String docId = posts.isEmpty() ? null : "doc-9";
                    return FakeTransport.json(200, json(runObj(status, "n1", null, single, "document", docId)));
                }
                if (url.endsWith("/company-data/connections/csc-1")) {
                    return FakeTransport.json(200, "{\"connection_id\":\"csc-1\",\"share_code\":\"ABC123\"}");
                }
                if (url.endsWith("/api/keys/ABC123")) {
                    return FakeTransport.json(200, "{\"public_key\":\"" + spki + "\"}");
                }
                throw new AssertionError("unexpected GET " + url);
            },
            (method, url, jsonBody, data) -> {
                posts.add(url);
                if (url.endsWith("/answers")) {
                    return FakeTransport.json(200, json(runObj("generating", "n1", null, single, "document", null)));
                }
                assertTrue(url.endsWith("/generate"), "unexpected write " + url);
                return FakeTransport.json(200, "{\"document_id\":\"doc-9\",\"status\":\"awaiting_signature\"}");
            });
        Client client = new Client(config(tmp), t);
        FlowRun run = client.processFlowRun("run-1", (node, answers) -> Map.of("company_name", "ACME BV"));
        assertTrue(posts.stream().anyMatch(p -> p.endsWith("/answers")));
        assertTrue(posts.stream().anyMatch(p -> p.endsWith("/generate")));
        assertEquals("awaiting_signature", run.status());
        assertEquals("doc-9", run.documentId());
    }

    @Test
    void processFlowRunNotOurTurn(@TempDir Path tmp) throws Exception {
        int[] calls = {0};
        ClientTest.RoutingTransport t = new ClientTest.RoutingTransport(
            (url, params) -> FakeTransport.json(200, json(runObj("awaiting_person", "n2", null, null, "data_only", null))));
        Client client = new Client(config(tmp), t);
        FlowRun run = client.processFlowRun("run-1", (node, answers) -> {
            calls[0]++;
            return Map.of("x", "y");
        });
        assertEquals("awaiting_person", run.status());
        assertEquals(0, calls[0]);
    }
}
