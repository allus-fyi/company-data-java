package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.ModelDeps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Output-model tests. Mirrors the Python reference's test_models. */
class ModelsTest {
    private static Map<String, Object> vector;
    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;
    private static Function<Object, String> decryptValue;

    @BeforeAll
    static void setUp() {
        vector = TestData.vector();
        privateKey = Crypto.loadPrivateKey(
            ((String) vector.get("encrypted_private_key_pem")).getBytes(StandardCharsets.UTF_8),
            (String) vector.get("passphrase"));
        publicKey = TestCrypto.publicKeyOf(privateKey);
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

    private static ModelDeps deps(Function<String, String> typeForSlug) {
        return new ModelDeps(decryptValue, typeForSlug, vu -> Wrapper.of(vector("binary")));
    }

    @SuppressWarnings("unchecked")
    private static Object vector(String section) {
        return ((Map<String, Object>) vector.get(section)).get("wrapper");
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── RequestField definitions ─────────────────────────────────────────────

    @Test
    void requestFieldsParsedAndMandatoryFolded() {
        Map<String, Object> body = Map.of("request_fields", List.of(
            field("work_email", "Work email", "email", false, true, false),
            field("logo", "Logo", "photo", true, false, false),
            field("ref", "Ref", "text", false, false, true)));
        List<RequestField> fields = RequestField.listFromApi(body);
        assertEquals(List.of("work_email", "logo", "ref"),
            fields.stream().map(RequestField::slug).toList());
        assertTrue(fields.get(0).mandatory());   // mandatory_provide
        assertFalse(fields.get(1).mandatory());
        assertTrue(fields.get(1).oneTime());
        assertTrue(fields.get(2).mandatory());   // mandatory_connected folds in
    }

    @Test
    void requestFieldCoercesXmlBoolStrings() {
        Map<String, Object> body = Map.of("request_fields", List.of(Map.of(
            "slug", "x", "label", "X", "type", "text",
            "one_time", "false", "mandatory_provide", "true", "mandatory_connected", "false")));
        RequestField f = RequestField.listFromApi(body).get(0);
        assertFalse(f.oneTime());
        assertTrue(f.mandatory());
    }

    private static Map<String, Object> field(String slug, String label, String type,
                                             boolean oneTime, boolean mp, boolean mc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slug", slug);
        m.put("label", label);
        m.put("type", type);
        m.put("one_time", oneTime);
        m.put("mandatory_provide", mp);
        m.put("mandatory_connected", mc);
        return m;
    }

    // ── Connection detail → typed, slug-keyed values ──────────────────────────

    @Test
    void connectionDetailTypedSlugKeyed() {
        Map<String, String> types = Map.of(
            "work_email", "email", "billing_address", "address", "dob", "date", "logo", "photo");
        ModelDeps deps = deps(types::get);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("work_email", entry(textWrapper(), true, "2026-06-17T10:00:00Z"));
        values.put("billing_address", entry(
            TestCrypto.encryptForKey(publicKey, "{\"city\":\"Utrecht\",\"country\":\"NL\"}"),
            false, "2026-06-16T09:00:00Z"));
        values.put("dob", entry(TestCrypto.encryptForKey(publicKey, "1990-04-23"), true, "2026-06-15T08:00:00Z"));
        Map<String, Object> logo = new LinkedHashMap<>();
        logo.put("value_url", "https://api.allme.fyi/api/company-data/connections/csc-1/slots/sf-9/file");
        logo.put("live", true);
        logo.put("updatedAt", "2026-06-14T07:00:00Z");
        values.put("logo", logo);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("connection_id", "csc-1");
        detail.put("user_id", "person-1");
        detail.put("values", values);
        Map<String, Object> identity = Map.of(
            "display_name", "Anna", "connected_at", "2026-06-10T00:00:00Z");

        Connection conn = Connection.fromApi(detail, deps, identity);

        assertEquals("csc-1", conn.id());
        assertEquals("person-1", conn.personId());
        assertEquals("Anna", conn.displayName());
        assertInstanceOf(OffsetDateTime.class, conn.connectedAt());
        assertSame(detail, conn.raw());

        Value email = conn.values().get("work_email");
        assertEquals(textPlaintext(), email.value());
        assertTrue(email.live());
        assertInstanceOf(OffsetDateTime.class, email.updatedAt());

        Value addr = conn.values().get("billing_address");
        assertEquals(Map.of("city", "Utrecht", "country", "NL"), addr.value());
        assertFalse(addr.live());

        Value dob = conn.values().get("dob");
        assertEquals(LocalDate.of(1990, 4, 23), dob.value());

        Value logoVal = conn.values().get("logo");
        assertInstanceOf(BinaryHandle.class, logoVal.value());
        assertTrue(((BinaryHandle) logoVal.value()).valueUrl().endsWith("/slots/sf-9/file"));
    }

    @Test
    void binaryHandleLazyFetchAndDecrypt() {
        @SuppressWarnings("unchecked")
        Map<String, Object> binary = (Map<String, Object>) vector.get("binary");
        Map<String, Boolean> captured = new LinkedHashMap<>();
        Function<String, Wrapper> fetch = url -> {
            captured.put(url, true);
            return Wrapper.of(((Map<String, Object>) binary).get("wrapper"));
        };
        ModelDeps deps = new ModelDeps(decryptValue, s -> "photo", fetch);

        Map<String, Object> logo = new LinkedHashMap<>();
        logo.put("value_url", "https://api.allme.fyi/api/company-data/connections/csc-1/slots/sf-9/file");
        logo.put("live", true);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("connection_id", "csc-1");
        detail.put("user_id", "person-1");
        detail.put("values", Map.of("logo", logo));

        Connection conn = Connection.fromApi(detail, deps, null);
        BinaryHandle handle = (BinaryHandle) conn.values().get("logo").value();
        assertTrue(captured.isEmpty()); // not fetched until .bytes()

        byte[] data = handle.bytes();
        assertEquals(1, captured.size());
        assertEquals(binary.get("inner_full_sha256"), sha256(data));
        handle.bytes(); // cached — still one fetch
        assertEquals(1, captured.size());
    }

    @Test
    void connectionHasNoPersonSourceField() {
        ModelDeps deps = deps(s -> "email");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("connection_id", "csc-1");
        detail.put("user_id", "person-1");
        detail.put("values", Map.of("work_email", entry(textWrapper(), true, null)));
        Connection conn = Connection.fromApi(detail, deps, null);
        String serialized = fyi.allme.allus.companydata.internal.Json.write(conn.raw());
        assertFalse(serialized.contains("field_id"));
        assertEquals(List.of("work_email"), List.copyOf(conn.values().keySet()));
    }

    private static Map<String, Object> entry(Object wrapper, boolean live, String updatedAt) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("value", wrapper);
        e.put("live", live);
        if (updatedAt != null) {
            e.put("updatedAt", updatedAt);
        }
        return e;
    }

    // ── Change events ──────────────────────────────────────────────────────────

    @Test
    void changeFieldUpdatedTypedAndIdPopulated() {
        ModelDeps deps = deps(s -> "email");
        Map<String, Object> fieldUpdated = new LinkedHashMap<>();
        fieldUpdated.put("id", "chg-42");
        fieldUpdated.put("event", "field_updated");
        fieldUpdated.put("person_user_id", "person-1");
        fieldUpdated.put("slug", "work_email");
        fieldUpdated.put("value", textWrapper());
        fieldUpdated.put("live", true);
        fieldUpdated.put("at", "2026-06-17T12:00:00Z");
        Map<String, Object> connCreated = new LinkedHashMap<>();
        connCreated.put("id", "chg-43");
        connCreated.put("event", "connection_created");
        connCreated.put("person_user_id", "person-2");
        connCreated.put("at", "2026-06-17T12:05:00Z");

        List<Change> changes = Change.listFromApi(
            Map.of("changes", List.of(fieldUpdated, connCreated)), deps);

        Change f = changes.get(0);
        assertEquals("chg-42", f.id());
        assertEquals("field_updated", f.event());
        assertEquals("person-1", f.personId());
        assertEquals("work_email", f.slug());
        assertEquals(textPlaintext(), f.value());
        assertEquals(Boolean.TRUE, f.live());
        assertInstanceOf(OffsetDateTime.class, f.at());

        Change c = changes.get(1);
        assertEquals("chg-43", c.id());
        assertEquals("connection_created", c.event());
        assertNull(c.slug());
        assertNull(c.value());
        assertNull(c.live());
    }

    @Test
    void changeFieldUpdatedBinaryIsLazyHandle() {
        @SuppressWarnings("unchecked")
        Map<String, Object> binary = (Map<String, Object>) vector.get("binary");
        Function<String, Wrapper> fetch = url -> Wrapper.of(binary.get("wrapper"));
        ModelDeps deps = new ModelDeps(decryptValue, s -> "photo", fetch);

        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("id", "chg-50");
        ev.put("event", "field_updated");
        ev.put("person_user_id", "person-1");
        ev.put("slug", "logo");
        ev.put("value_url", "https://api.allme.fyi/api/company-data/connections/csc-1/slots/sf-9/file");
        ev.put("live", true);
        ev.put("at", "2026-06-17T12:00:00Z");

        List<Change> changes = Change.listFromApi(Map.of("changes", List.of(ev)), deps);
        Change chg = changes.get(0);
        assertInstanceOf(BinaryHandle.class, chg.value());
        assertEquals(binary.get("inner_full_sha256"), sha256(((BinaryHandle) chg.value()).bytes()));
    }

    @Test
    void changeConsentEventHasSlugNoValue() {
        ModelDeps deps = new ModelDeps(w -> "", s -> "email", null);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("id", "chg-9");
        ev.put("event", "consent_accepted");
        ev.put("person_user_id", "p");
        ev.put("slug", "work_email");
        ev.put("at", "2026-06-17T00:00:00Z");
        Change chg = Change.listFromApi(Map.of("changes", List.of(ev)), deps).get(0);
        assertEquals("consent_accepted", chg.event());
        assertEquals("work_email", chg.slug());
        assertNull(chg.value()); // consent events carry no value
    }

    /** connection_request_accepted/_rejected (idea 2) surface request_id; no slot/value. */
    @Test
    void changeConnectRequestOutcomeEventsCarryRequestId() {
        ModelDeps deps = new ModelDeps(w -> "", s -> null, null);

        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("id", "c1");
        accepted.put("event", "connection_request_accepted");
        accepted.put("request_id", "req-9");
        accepted.put("person_user_id", "person-1");
        accepted.put("share_code", "P1CODE");
        accepted.put("at", "2026-06-23T10:00:00Z");

        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("id", "c2");
        rejected.put("event", "connection_request_rejected");
        rejected.put("request_id", "req-8");
        rejected.put("person_user_id", "person-2");

        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", "c3");
        created.put("event", "connection_created");
        created.put("person_user_id", "person-3");

        List<Change> changes = Change.listFromApi(
            Map.of("changes", List.of(accepted, rejected, created)), deps);

        assertEquals("connection_request_accepted", changes.get(0).event());
        assertEquals("req-9", changes.get(0).requestId());
        assertEquals("person-1", changes.get(0).personId());
        assertEquals("P1CODE", changes.get(0).shareCode());
        assertNull(changes.get(0).slug());
        assertNull(changes.get(0).value());

        assertEquals("req-8", changes.get(1).requestId());
        assertNull(changes.get(2).requestId()); // unrelated event
    }

    // ── LogEntry ──────────────────────────────────────────────────────────────

    @Test
    void logEntriesParsed() {
        Map<String, Object> e1 = new LinkedHashMap<>();
        e1.put("type", "email");
        e1.put("message", "stale-queue alert");
        e1.put("metadata", Map.of("days", 3));
        e1.put("at", "2026-06-17T06:00:00Z");
        Map<String, Object> e2 = new LinkedHashMap<>();
        e2.put("type", "purge");
        e2.put("message", "purged 4 changes");
        e2.put("metadata", Map.of("count", 4));
        e2.put("created_at", "2026-06-17T07:00:00Z");

        List<LogEntry> logs = LogEntry.listFromApi(Map.of("total", 2, "items", List.of(e1, e2)));
        assertEquals(2, logs.size());
        assertEquals("email", logs.get(0).type());
        assertEquals(Map.of("days", 3), logs.get(0).metadata());
        assertInstanceOf(OffsetDateTime.class, logs.get(0).at());
        assertInstanceOf(OffsetDateTime.class, logs.get(1).at()); // created_at fallback
    }

    /** Every change event carries the person's profile share_code (nullable). */
    @Test
    void changeIncludesShareCode() {
        ModelDeps deps = new ModelDeps(decryptValue, s -> null, null);
        Map<String, Object> withCode = new LinkedHashMap<>();
        withCode.put("id", "chg-1");
        withCode.put("event", "connection_created");
        withCode.put("person_user_id", "person-1");
        withCode.put("share_code", "ABC123");
        withCode.put("at", "2026-06-17T12:00:00Z");
        Map<String, Object> noCode = new LinkedHashMap<>();
        noCode.put("id", "chg-2");
        noCode.put("event", "connection_created");
        noCode.put("person_user_id", "person-2"); // no share_code -> null
        noCode.put("at", "2026-06-17T12:00:00Z");

        List<Change> changes = Change.listFromApi(
            Map.of("changes", List.of(withCode, noCode)), deps);

        assertEquals("ABC123", changes.get(0).shareCode());
        assertNull(changes.get(1).shareCode());
    }

    // ── document_status_changed change + Document model ─────────────────────────

    @Test
    void changeDocumentStatusChangedParses() {
        ModelDeps deps = new ModelDeps(decryptValue, s -> null, null);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("id", "chg-doc");
        ev.put("event", "document_status_changed");
        ev.put("person_user_id", "u-1");
        ev.put("share_code", "ABC123");
        ev.put("document_id", "doc-9");
        ev.put("status", "ended");
        ev.put("at", "2026-06-22T10:00:00Z");

        Change chg = Change.listFromApi(Map.of("changes", List.of(ev)), deps).get(0);
        assertEquals("document_status_changed", chg.event());
        assertEquals("doc-9", chg.documentId());
        assertEquals("ended", chg.status());
        assertEquals("u-1", chg.personId());
        assertEquals("ABC123", chg.shareCode());
        assertNull(chg.slug());
        assertNull(chg.value());
        assertNull(chg.live());
    }

    @Test
    void changeDocumentStatusChangedCarriesAction() {
        ModelDeps deps = new ModelDeps(decryptValue, s -> null, null);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("id", "chg-sign");
        ev.put("event", "document_status_changed");
        ev.put("person_user_id", "u-2");
        ev.put("action", "signed");
        ev.put("document_id", "doc-7");
        ev.put("status", "active");
        ev.put("at", "2026-06-22T10:00:00Z");

        Change chg = Change.listFromApi(Map.of("changes", List.of(ev)), deps).get(0);
        assertEquals("document_status_changed", chg.event());
        assertEquals("signed", chg.action());
        assertEquals("doc-7", chg.documentId());
        assertEquals("active", chg.status());
        assertNull(chg.slug());
    }

    @Test
    void documentModelCarriesContractFlagsAndSignatures() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("id", "c1");
        obj.put("kind", "agreement");
        obj.put("name", "Agreement");
        obj.put("status", "active");
        obj.put("payload_kind", "json");
        obj.put("is_private", false);
        obj.put("value", Map.of("v", 1));
        obj.put("metadata", Map.of());
        obj.put("requires_signature", true);
        obj.put("requires_acceptance", false);
        obj.put("signatures", List.of(Map.of("action", "signed", "method", "biometric")));
        Document doc = Document.fromApi(obj, null);
        assertTrue(doc.requiresSignature());
        assertFalse(doc.requiresAcceptance());
        assertEquals(1, doc.signatures().size());
        assertEquals("signed", doc.signatures().get(0).get("action"));
    }

    @Test
    void documentModelBroadcastJsonIsPlaintext() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("id", "d1");
        obj.put("kind", "document");
        obj.put("name", "Terms");
        obj.put("status", "active");
        obj.put("payload_kind", "json");
        obj.put("is_private", false);
        obj.put("value", Map.of("v", 1));
        obj.put("metadata", Map.of());
        Document doc = Document.fromApi(obj, null);
        assertEquals(Map.of("v", 1), doc.json()); // no decrypt needed
    }

    @Test
    void documentModelPerPersonJsonDecrypts() {
        Map<String, Object> wrapper = TestCrypto.encryptForKey(publicKey, "{\"plan\":\"pro\"}");
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("id", "d2");
        obj.put("kind", "document");
        obj.put("name", "PP");
        obj.put("status", "active");
        obj.put("payload_kind", "json");
        obj.put("is_private", true);
        obj.put("value", wrapper);
        obj.put("metadata", Map.of());
        Document doc = Document.fromApi(obj, decryptValue);
        assertEquals(Map.of("plan", "pro"), doc.json()); // decrypted via injected decrypt
    }
}
