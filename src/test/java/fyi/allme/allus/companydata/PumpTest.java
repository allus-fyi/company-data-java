package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.ModelDeps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crash-safe changes-pump tests. Mirrors the Python reference's test_pump. */
class PumpTest {
    private static Map<String, Object> vector;
    private static RSAPrivateKey privateKey;
    private static Map<String, Object> cipherWrapper;
    private static String expectedPlaintext;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void setUp() {
        vector = TestData.vector();
        privateKey = Crypto.loadPrivateKey(
            ((String) vector.get("encrypted_private_key_pem")).getBytes(StandardCharsets.UTF_8),
            (String) vector.get("passphrase"));
        Map<String, Object> text = (Map<String, Object>) vector.get("text");
        cipherWrapper = (Map<String, Object>) text.get("wrapper");
        expectedPlaintext = (String) text.get("plaintext");
    }

    private static Config config(Path tmp) {
        return Config.builder()
            .apiUrl("https://api.example.test")
            .clientId("svc_test")
            .clientSecret("secret")
            .servicePrivateKey("unused.pem")
            .keyPassphrase((String) vector.get("passphrase"))
            .cacheDir(tmp.resolve("allus-cache").toString())
            .build();
    }

    private static Pump.DecryptChange decryptChange() {
        ModelDeps deps = new ModelDeps(
            w -> Crypto.decrypt(Wrapper.of(w), privateKey), slug -> "text", null);
        return event -> Change.fromApi(event, deps);
    }

    private static List<Map<String, Object>> makeEvents(int count, int start) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = start; i < start + count; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", String.format("chg-%04d", i));
            e.put("event", "field_updated");
            e.put("person_user_id", "person-" + i);
            e.put("slug", "work_email");
            e.put("value", cipherWrapper); // ciphertext, exactly as the API serves it
            e.put("live", true);
            e.put("at", "2026-06-17T10:0" + i + ":00Z");
            events.add(e);
        }
        return events;
    }

    /** In-memory drain-on-fetch queue: fetch deletes exactly what it returns. */
    static final class FakeSource {
        final List<Map<String, Object>> queue;
        final List<Integer> fetchCalls = new ArrayList<>();

        FakeSource(List<Map<String, Object>> events) {
            this.queue = new ArrayList<>(events);
        }

        List<Map<String, Object>> fetch(int limit) {
            fetchCalls.add(limit);
            int n = Math.min(limit, queue.size());
            List<Map<String, Object>> batch = new ArrayList<>(queue.subList(0, n));
            for (int i = 0; i < n; i++) {
                queue.remove(0);
            }
            return batch;
        }
    }

    private static Pump pump(Config config, FakeSource source) {
        return new Pump(config, source::fetch, decryptChange(),
            d -> { }, java.util.logging.Logger.getLogger("test"));
    }

    private static String sha256(byte[] b) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── (a) persist-before-deliver ─────────────────────────────────────────────

    @Test
    void batchPersistedBeforeAnyHandlerCall(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(3, 1));
        AtomicInteger pendingAtFirst = new AtomicInteger(-1);

        Pump pump = pump(config, source);
        pump.processChanges(change -> {
            if (pendingAtFirst.get() == -1) {
                FileBuffer buf = new FileBuffer(config.cacheDir());
                pendingAtFirst.set(buf.pending().size());
            }
        });
        assertEquals(3, pendingAtFirst.get());
    }

    // ── (b) ack on success ──────────────────────────────────────────────────────

    @Test
    void handlerSuccessAcksPendingFile(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(3, 1));
        List<String> seen = new ArrayList<>();
        pump(config, source).processChanges(c -> seen.add(c.id()));
        assertEquals(List.of("chg-0001", "chg-0002", "chg-0003"), seen);
        FileBuffer buf = new FileBuffer(config.cacheDir());
        assertTrue(buf.pending().isEmpty());
        assertTrue(buf.deadLetters().isEmpty());
    }

    @Test
    void deliveredChangeIsDecryptedPlaintext(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(1, 1));
        List<Change> delivered = new ArrayList<>();
        pump(config, source).processChanges(delivered::add);
        assertEquals(1, delivered.size());
        assertEquals(expectedPlaintext, delivered.get(0).value());
    }

    // ── (c) retry → dead-letter → continue ──────────────────────────────────────

    @Test
    void poisonEventDeadLetteredOthersProcessed(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(3, 1));
        AtomicInteger attempts = new AtomicInteger(0);
        List<String> deliveredOk = new ArrayList<>();

        pump(config, source).processChanges(change -> {
            if (change.id().equals("chg-0002")) {
                attempts.incrementAndGet();
                throw new RuntimeException("poison");
            }
            deliveredOk.add(change.id());
        }, Pump.Options.defaults().withMaxRetries(3));

        assertEquals(4, attempts.get()); // 1 + max_retries, then dead-lettered
        assertEquals(List.of("chg-0001", "chg-0003"), deliveredOk);

        FileBuffer buf = new FileBuffer(config.cacheDir());
        assertTrue(buf.pending().isEmpty());
        List<Map<String, Object>> dl = buf.deadLetters();
        assertEquals(List.of("chg-0002"), dl.stream().map(d -> (String) d.get("id")).toList());
        assertTrue(((String) dl.get(0).get("error")).contains("poison"));
        assertEquals(4, ((Number) dl.get(0).get("attempts")).intValue());
    }

    @Test
    void onErrorHaltRaisesAndLeavesPending(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(3, 1));

        assertThrows(RuntimeException.class, () ->
            pump(config, source).processChanges(change -> {
                if (change.id().equals("chg-0002")) {
                    throw new RuntimeException("halt-me");
                }
            }, Pump.Options.defaults().withMaxRetries(1).withOnError(Pump.OnError.HALT)));

        FileBuffer buf = new FileBuffer(config.cacheDir());
        List<String> pendingIds = buf.pending().stream().map(e -> (String) e.get("id")).toList();
        // chg-0001 acked; chg-0002 (failed) + chg-0003 (never reached) still pending.
        assertEquals(List.of("chg-0002", "chg-0003"), pendingIds);
    }

    // ── (d) crash test ──────────────────────────────────────────────────────────

    @Test
    void crashAfterOneThenReplayOnRestart(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(3, 1));
        List<String> deliveredRun1 = new ArrayList<>();

        class Crash extends RuntimeException {
        }

        assertThrows(Crash.class, () ->
            pump(config, source).processChanges(change -> {
                deliveredRun1.add(change.id());
                if (deliveredRun1.size() == 1) {
                    return; // #1 succeeds → acked
                }
                throw new Crash(); // process dies right after #1's ack, before #2/#3
            }, Pump.Options.defaults().withMaxRetries(0).withOnError(Pump.OnError.HALT)));

        assertEquals(List.of("chg-0001", "chg-0002"), deliveredRun1);
        FileBuffer bufMid = new FileBuffer(config.cacheDir());
        assertEquals(List.of("chg-0002", "chg-0003"),
            bufMid.pending().stream().map(e -> (String) e.get("id")).toList());

        // Restart: a brand-new pump on the SAME cache_dir, empty source.
        FakeSource empty = new FakeSource(List.of());
        List<String> deliveredRun2 = new ArrayList<>();
        pump(config, empty).processChanges(c -> deliveredRun2.add(c.id()));

        assertEquals(List.of("chg-0002", "chg-0003"), deliveredRun2);
        assertFalse(empty.fetchCalls.isEmpty());
        assertTrue(empty.fetchCalls.get(0) >= 1);

        assertTrue(new FileBuffer(config.cacheDir()).pending().isEmpty());
    }

    @Test
    void idempotentChangeIdStableAcrossReplay(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(2, 1));
        List<Object[]> run1 = new ArrayList<>();

        class Crash extends RuntimeException {
        }

        assertThrows(Crash.class, () ->
            pump(config, source).processChanges(change -> {
                run1.add(new Object[]{change.id(), change.value()});
                throw new Crash(); // crash immediately → both stay pending
            }, Pump.Options.defaults().withMaxRetries(0).withOnError(Pump.OnError.HALT)));

        List<Object[]> run2 = new ArrayList<>();
        FakeSource empty = new FakeSource(List.of());
        pump(config, empty).processChanges(c -> run2.add(new Object[]{c.id(), c.value()}));

        assertEquals("chg-0001", run1.get(0)[0]);
        assertEquals("chg-0001", run2.get(0)[0]);
        assertEquals(run1.get(0)[1], run2.get(0)[1]); // same decrypted value
    }

    // ── (e) ciphertext at rest ────────────────────────────────────────────────

    @Test
    void bufferFilesStoreCiphertextNotPlaintext(@TempDir Path tmp) throws Exception {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(2, 1));

        class Stop extends RuntimeException {
        }

        assertThrows(Stop.class, () ->
            pump(config, source).processChanges(c -> {
                throw new Stop();
            }, Pump.Options.defaults().withMaxRetries(0).withOnError(Pump.OnError.HALT)));

        Path pendingDir = Path.of(config.cacheDir()).resolve("pending");
        List<Path> files;
        try (var s = Files.list(pendingDir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
        }
        assertFalse(files.isEmpty());
        for (Path f : files) {
            String rawText = Files.readString(f, StandardCharsets.UTF_8);
            assertFalse(rawText.contains(expectedPlaintext)); // plaintext NEVER on disk
            Map<String, Object> stored = fyi.allme.allus.companydata.internal.Json.parseObject(rawText);
            @SuppressWarnings("unchecked")
            Map<String, Object> value = (Map<String, Object>) stored.get("value");
            assertEquals(1, ((Number) value.get("_enc")).intValue());
            assertEquals(cipherWrapper.get("k"), value.get("k"));
        }
    }

    // ── (f) returns when drained ────────────────────────────────────────────────

    @Test
    void processChangesReturnsWhenSourceDrained(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(5, 1));
        List<String> delivered = new ArrayList<>();
        pump(config, source).processChanges(c -> delivered.add(c.id()),
            Pump.Options.defaults().withBatchSize(2)); // small batch → multiple cycles
        assertEquals(List.of("chg-0001", "chg-0002", "chg-0003", "chg-0004", "chg-0005"), delivered);
        assertTrue(source.queue.isEmpty());
        assertEquals(2, source.fetchCalls.get(source.fetchCalls.size() - 1));
    }

    @Test
    void emptySourceReturnsImmediately(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(List.of());
        List<Change> delivered = new ArrayList<>();
        pump(config, source).processChanges(delivered::add);
        assertTrue(delivered.isEmpty());
        assertEquals(List.of(100), source.fetchCalls); // one drain, default batch, got nothing
    }

    @Test
    void batchSizeClampedTo500(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(1, 1));
        pump(config, source).processChanges(c -> { }, Pump.Options.defaults().withBatchSize(9999));
        assertEquals(500, source.fetchCalls.stream().mapToInt(Integer::intValue).max().orElseThrow());
    }

    // ── drain_batch primitive + dead-letter retry ───────────────────────────────

    @Test
    void drainBatchIsRawUnbuffered(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(3, 1));
        Pump pump = pump(config, source);
        List<Change> batch = pump.drainBatch(2);
        assertEquals(List.of("chg-0001", "chg-0002"), batch.stream().map(Change::id).toList());
        FileBuffer buf = new FileBuffer(config.cacheDir());
        assertTrue(buf.pending().isEmpty()); // nothing buffered
        assertEquals(List.of(2), source.fetchCalls);
    }

    @Test
    void drainBatchClampedTo500(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(List.of());
        pump(config, source).drainBatch(10_000);
        assertEquals(List.of(500), source.fetchCalls);
    }

    @Test
    void retryDeadLettersRedrives(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(2, 1));
        Pump pump = pump(config, source);
        pump.processChanges(change -> {
            if (change.id().equals("chg-0002")) {
                throw new RuntimeException("boom");
            }
        }, Pump.Options.defaults().withMaxRetries(1));

        FileBuffer buf = new FileBuffer(config.cacheDir());
        assertEquals(List.of("chg-0002"), buf.deadLetters().stream().map(d -> (String) d.get("id")).toList());

        List<String> redriven = new ArrayList<>();
        pump.retryDeadLetters(c -> redriven.add(c.id()));
        assertEquals(List.of("chg-0002"), redriven);
        assertTrue(new FileBuffer(config.cacheDir()).deadLetters().isEmpty());
    }

    @Test
    void retryDeadLettersStillFailingStaysDeadletteredNeverPending(@TempDir Path tmp) throws Exception {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(2, 1));
        Pump pump = pump(config, source);
        pump.processChanges(change -> {
            if (change.id().equals("chg-0002")) {
                throw new RuntimeException("boom");
            }
        }, Pump.Options.defaults().withMaxRetries(1));

        FileBuffer buf = new FileBuffer(config.cacheDir());
        List<Map<String, Object>> dl0 = buf.deadLetters();
        assertEquals(List.of("chg-0002"), dl0.stream().map(d -> (String) d.get("id")).toList());
        assertEquals(2, ((Number) dl0.get(0).get("attempts")).intValue());

        Path pendingDir = Path.of(config.cacheDir()).resolve("pending");
        Path deadletterDir = Path.of(config.cacheDir()).resolve("deadletter");

        int redriven = pump.retryDeadLetters(change -> {
            if (change.id().equals("chg-0002")) {
                throw new RuntimeException("boom");
            }
        }, Pump.Options.defaults().withMaxRetries(2));
        assertEquals(0, redriven);

        FileBuffer buf2 = new FileBuffer(config.cacheDir());
        List<Map<String, Object>> dl1 = buf2.deadLetters();
        assertEquals(List.of("chg-0002"), dl1.stream().map(d -> (String) d.get("id")).toList());
        assertEquals(3, ((Number) dl1.get(0).get("attempts")).intValue()); // 1 + 2 re-drives
        assertTrue(((String) dl1.get(0).get("error")).contains("boom"));
        assertTrue(buf2.pending().isEmpty());
        try (var s = Files.list(pendingDir)) {
            assertEquals(0, s.count());
        }
        try (var s = Files.list(deadletterDir)) {
            assertEquals(1, s.filter(p -> p.getFileName().toString().endsWith(".json")).count());
        }

        List<String> ok = new ArrayList<>();
        int again = pump.retryDeadLetters(ok2 -> ok.add(ok2.id()));
        assertEquals(1, again);
        assertEquals(List.of("chg-0002"), ok);
        assertTrue(new FileBuffer(config.cacheDir()).deadLetters().isEmpty());
        assertTrue(new FileBuffer(config.cacheDir()).pending().isEmpty());
    }

    @Test
    void retryDeadLettersAttemptsMonotonicAcrossRuns(@TempDir Path tmp) {
        Config config = config(tmp);
        FakeSource source = new FakeSource(makeEvents(2, 1));
        Pump pump = pump(config, source);
        pump.processChanges(change -> {
            if (change.id().equals("chg-0002")) {
                throw new RuntimeException("boom");
            }
        }, Pump.Options.defaults().withMaxRetries(3));
        FileBuffer buf = new FileBuffer(config.cacheDir());
        assertEquals(4, ((Number) buf.deadLetters().get(0).get("attempts")).intValue());

        // Re-drive with a SMALLER budget (run-local attempts = 1) → stays clamped at 4.
        assertEquals(0, pump.retryDeadLetters(change -> {
            if (change.id().equals("chg-0002")) {
                throw new RuntimeException("boom");
            }
        }, Pump.Options.defaults().withMaxRetries(0)));
        List<Map<String, Object>> dl1 = new FileBuffer(config.cacheDir()).deadLetters();
        assertEquals(4, ((Number) dl1.get(0).get("attempts")).intValue()); // monotonic — NOT 1
    }

    // ── Fix 1: a poison-decrypt event must not wedge the stream ─────────────────

    private static Map<String, Object> makePoisonEvent(String id) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("event", "field_updated");
        e.put("person_user_id", "person-x");
        e.put("slug", "work_email");
        // A structurally-bogus wrapper → DecryptException at delivery (never on disk).
        e.put("value", Map.of("_enc", 1, "k", "@@notbase64@@", "iv", "AAAA", "d", "AAAA"));
        e.put("live", true);
        e.put("at", "2026-06-17T10:09:00Z");
        return e;
    }

    @Test
    void poisonDecryptDeadLettersWithoutWedging(@TempDir Path tmp) throws Exception {
        Config config = config(tmp);
        Map<String, Integer> decryptCalls = new HashMap<>();
        decryptCalls.put("chg-0002", 0);

        Pump.DecryptChange decrypt = event -> {
            String cid = (String) event.get("id");
            if ("chg-0002".equals(cid)) {
                decryptCalls.merge("chg-0002", 1, Integer::sum);
                throw new DecryptException("corrupt ciphertext for chg-0002");
            }
            ModelDeps deps = new ModelDeps(w -> Crypto.decrypt(Wrapper.of(w), privateKey), s -> "text", null);
            return Change.fromApi(event, deps);
        };

        List<Map<String, Object>> events = new ArrayList<>(makeEvents(1, 1));
        events.add(makePoisonEvent("chg-0002"));
        events.addAll(makeEvents(1, 3));
        FakeSource source = new FakeSource(events);

        List<String> delivered = new ArrayList<>();
        Pump pump = new Pump(config, source::fetch, decrypt, d -> { },
            java.util.logging.Logger.getLogger("test"));
        pump.processChanges(c -> delivered.add(c.id()), Pump.Options.defaults().withMaxRetries(3));

        assertEquals(List.of("chg-0001", "chg-0003"), delivered);
        assertEquals(1, decryptCalls.get("chg-0002")); // dead-lettered IMMEDIATELY, no retries

        FileBuffer buf = new FileBuffer(config.cacheDir());
        assertTrue(buf.pending().isEmpty());
        List<Map<String, Object>> dl = buf.deadLetters();
        assertEquals(List.of("chg-0002"), dl.stream().map(d -> (String) d.get("id")).toList());
        assertTrue(((String) dl.get(0).get("error")).contains("DecryptException"));
        assertEquals(1, ((Number) dl.get(0).get("attempts")).intValue());

        try (var s = Files.list(Path.of(config.cacheDir()).resolve("pending"))) {
            assertEquals(0, s.count());
        }

        // A fresh pump must NOT re-deliver the poison event.
        List<String> delivered2 = new ArrayList<>();
        FakeSource empty = new FakeSource(List.of());
        new Pump(config, empty::fetch, decrypt, d -> { }, java.util.logging.Logger.getLogger("test"))
            .processChanges(c -> delivered2.add(c.id()));
        assertTrue(delivered2.isEmpty());
        assertEquals(List.of("chg-0002"),
            new FileBuffer(config.cacheDir()).deadLetters().stream().map(d -> (String) d.get("id")).toList());
    }

    @Test
    void poisonDecryptWithHaltReraises(@TempDir Path tmp) {
        Config config = config(tmp);
        Pump.DecryptChange decrypt = event -> {
            if ("chg-0001".equals(event.get("id"))) {
                throw new DecryptException("undecryptable");
            }
            ModelDeps deps = new ModelDeps(w -> Crypto.decrypt(Wrapper.of(w), privateKey), s -> "text", null);
            return Change.fromApi(event, deps);
        };
        FakeSource source = new FakeSource(List.of(makePoisonEvent("chg-0001")));
        Pump pump = new Pump(config, source::fetch, decrypt, d -> { },
            java.util.logging.Logger.getLogger("test"));
        assertThrows(DecryptException.class, () ->
            pump.processChanges(c -> { }, Pump.Options.defaults().withOnError(Pump.OnError.HALT)));
        FileBuffer buf = new FileBuffer(config.cacheDir());
        assertEquals(List.of("chg-0001"), buf.pending().stream().map(e -> (String) e.get("id")).toList());
    }
}
