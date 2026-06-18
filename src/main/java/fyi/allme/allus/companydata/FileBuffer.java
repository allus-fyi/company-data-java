package fyi.allme.allus.companydata;

import fyi.allme.allus.companydata.internal.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Durable plain-file buffer for the crash-safe changes pump.
 *
 * <p>The changes feed is a server-side drain-on-fetch queue (a fetch returns up to
 * N events and deletes those rows in the same transaction — the API keeps no copy),
 * so a drained batch MUST be persisted locally BEFORE any delivery, or a consumer
 * crash mid-batch loses events the API already deleted. This is that persistence:
 * a zero-dependency, plain-file buffer under {@code cache_dir}.
 *
 * <pre>
 * &lt;cache_dir&gt;/pending/&lt;seq&gt;_&lt;change_id&gt;.json     # one un-acked event, oldest-first
 * &lt;cache_dir&gt;/deadletter/&lt;seq&gt;_&lt;change_id&gt;.json  # events that exhausted retries
 * </pre>
 *
 * <ul>
 *   <li>The stored event is the <b>raw hardened API event</b> — its {@code value}/
 *       {@code value_url} is <b>CIPHERTEXT</b>, never the decrypted plaintext. No
 *       PII is ever written to disk ("ciphertext at rest").</li>
 *   <li>{@code <seq>} is a zero-padded, monotonically increasing sequence persisted
 *       in {@code <cache_dir>/.seq}; sorting filenames lexicographically yields
 *       oldest-first, stable even when event timestamps are missing/equal.</li>
 *   <li>Writes are crash-safe: temp file → {@code force(true)} fsync → atomic
 *       {@code Files.move(ATOMIC_MOVE)} → directory fsync.</li>
 *   <li>{@link #ack(String)} deletes the pending file; {@link #deadLetter} moves it
 *       to {@code deadletter/} with the error + attempt count. Neither re-fetches
 *       from the API (it already deleted the row) — the buffer is the only home.</li>
 * </ul>
 */
public final class FileBuffer {
    private static final String PENDING_DIR = "pending";
    private static final String DEADLETTER_DIR = "deadletter";
    private static final String SEQ_FILE = ".seq";
    private static final int SEQ_WIDTH = 16;
    private static final String DEADLETTER_KEY = "_deadletter";

    private final Path pendingDir;
    private final Path deadletterDir;
    private final Path seqPath;
    private final Object lock = new Object();

    public FileBuffer(String cacheDir) {
        Path dir = Path.of(cacheDir);
        this.pendingDir = dir.resolve(PENDING_DIR);
        this.deadletterDir = dir.resolve(DEADLETTER_DIR);
        this.seqPath = dir.resolve(SEQ_FILE);
        try {
            Files.createDirectories(pendingDir);
            Files.createDirectories(deadletterDir);
        } catch (IOException exc) {
            throw new RuntimeException("could not create buffer dirs under " + cacheDir + ": " + exc.getMessage(), exc);
        }
    }

    // ── sequence ─────────────────────────────────────────────────────────────

    private long nextSeq() {
        synchronized (lock) {
            Long current = readSeq();
            long base = current != null ? current : maxOnDiskSeq();
            long next = base + 1;
            writeSeqInt(next);
            return next;
        }
    }

    private Long readSeq() {
        try {
            String s = Files.readString(seqPath, StandardCharsets.UTF_8).strip();
            return Long.parseLong(s);
        } catch (IOException | NumberFormatException exc) {
            return null;
        }
    }

    private long maxOnDiskSeq() {
        long best = 0;
        for (Path d : List.of(pendingDir, deadletterDir)) {
            try (Stream<Path> s = Files.list(d)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    Long seq = seqOf(p.getFileName().toString());
                    if (seq != null && seq > best) {
                        best = seq;
                    }
                }
            } catch (IOException ignored) {
                // best-effort
            }
        }
        return best;
    }

    // ── append / list / ack ──────────────────────────────────────────────────

    /**
     * Persist a drained batch (oldest-first), each in its own fsync'd file (the
     * backup the API no longer holds; MUST complete before the pump delivers).
     * Returns the pending filenames written.
     */
    public List<String> append(List<Map<String, Object>> events) {
        List<String> written = new ArrayList<>();
        for (Map<String, Object> event : events) {
            long seq = nextSeq();
            String changeId = sanitizeId(event.get("id"));
            String name = String.format("%0" + SEQ_WIDTH + "d_%s.json", seq, changeId);
            atomicWriteJson(pendingDir.resolve(name), event);
            written.add(name);
        }
        return written;
    }

    /** All un-acked events, oldest-first (by the sortable filename). */
    public List<Map<String, Object>> pending() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : pendingFiles()) {
            out.add(readEvent(pendingDir, name));
        }
        return out;
    }

    private List<String> pendingFiles() {
        return jsonFilesSorted(pendingDir);
    }

    private List<String> jsonFilesSorted(Path dir) {
        List<String> names = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s::iterator) {
                String n = p.getFileName().toString();
                if (n.endsWith(".json") && !n.startsWith(".tmp_")) {
                    names.add(n);
                }
            }
        } catch (IOException ignored) {
            // best-effort
        }
        names.sort(String::compareTo); // zero-padded seq prefix → oldest-first
        return names;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readEvent(Path dir, String name) {
        try {
            String text = Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
            return Json.parseObject(text);
        } catch (IOException exc) {
            throw new RuntimeException("could not read buffer file " + name + ": " + exc.getMessage(), exc);
        }
    }

    private String findPendingFile(String changeId) {
        String target = sanitizeId(changeId);
        for (String name : pendingFiles()) {
            if (name.substring(name.indexOf('_') + 1).equals(target + ".json")) {
                return name;
            }
        }
        return null;
    }

    /** Delete the pending file for {@code changeId} (per-item ack). Idempotent. */
    public boolean ack(String changeId) {
        String name = findPendingFile(changeId);
        if (name == null) {
            return false;
        }
        try {
            boolean deleted = Files.deleteIfExists(pendingDir.resolve(name));
            fsyncDir(pendingDir);
            return deleted;
        } catch (IOException exc) {
            return false;
        }
    }

    // ── dead-letter ────────────────────────────────────────────────────────────

    /**
     * Move a poison event from pending → deadletter with error + attempts.
     *
     * <p>At-least-once safe: the new dead-letter copy is written BEFORE the pending
     * copy is unlinked, so a crash between them leaves the event in both dirs →
     * harmless re-delivery on replay (the id-dedup handler absorbs it). Do NOT
     * "fix" this by deleting-first.
     */
    public boolean deadLetter(String changeId, String error, int attempts) {
        String name = findPendingFile(changeId);
        if (name == null) {
            return false;
        }
        Map<String, Object> event = readEvent(pendingDir, name);
        Map<String, Object> record = new LinkedHashMap<>(event);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("error", String.valueOf(error));
        meta.put("attempts", attempts);
        record.put(DEADLETTER_KEY, meta);
        atomicWriteJson(deadletterDir.resolve(name), record); // write new copy FIRST
        try {
            Files.deleteIfExists(pendingDir.resolve(name));    // then unlink pending
        } catch (IOException ignored) {
            // best-effort
        }
        fsyncDir(pendingDir);
        return true;
    }

    private List<String> deadletterFiles() {
        return jsonFilesSorted(deadletterDir);
    }

    /**
     * All dead-lettered events, oldest-first — each the stored (ciphertext) event
     * with a flattened {@code error} + {@code attempts} lifted out of the reserved
     * {@code _deadletter} block.
     */
    public List<Map<String, Object>> deadLetters() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : deadletterFiles()) {
            Map<String, Object> event = readEvent(deadletterDir, name);
            Object metaObj = event.get(DEADLETTER_KEY);
            Map<String, Object> item = new LinkedHashMap<>(event);
            if (metaObj instanceof Map<?, ?> meta) {
                item.put("error", meta.get("error"));
                item.put("attempts", meta.get("attempts"));
            } else {
                item.put("error", null);
                item.put("attempts", null);
            }
            out.add(item);
        }
        return out;
    }

    private String findDeadletterFile(String changeId) {
        String target = sanitizeId(changeId);
        for (String name : deadletterFiles()) {
            if (name.substring(name.indexOf('_') + 1).equals(target + ".json")) {
                return name;
            }
        }
        return null;
    }

    /**
     * Rewrite a dead-letter record IN PLACE with a refreshed error + attempts (plan
     * §Durability caveat 2).
     *
     * <p>Used by a still-failing re-drive: the record stays in {@code deadletter/}
     * and is updated atomically (temp file inside {@code deadletter/} → fsync →
     * {@code Files.move} over the same path). It is NEVER routed back through
     * {@code pending/}, so a crash anywhere leaves it as the old or new dead-letter
     * — never a resurrected live event. The stored attempt count is monotonic
     * across runs: {@code max(existing, new)} (caveat 3). Idempotent.
     */
    public boolean updateDeadLetter(String changeId, String error, int attempts) {
        String name = findDeadletterFile(changeId);
        if (name == null) {
            return false;
        }
        Path path = deadletterDir.resolve(name);
        Map<String, Object> event;
        try {
            event = readEvent(deadletterDir, name);
        } catch (RuntimeException exc) {
            return false;
        }
        int priorAttempts = 0;
        Object metaObj = event.get(DEADLETTER_KEY);
        if (metaObj instanceof Map<?, ?> meta) {
            Object prior = meta.get("attempts");
            if (prior instanceof Number n) {
                priorAttempts = n.intValue();
            } else if (prior != null) {
                try {
                    priorAttempts = Integer.parseInt(String.valueOf(prior));
                } catch (NumberFormatException ignored) {
                    priorAttempts = 0;
                }
            }
        }
        Map<String, Object> record = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : event.entrySet()) {
            if (!e.getKey().equals(DEADLETTER_KEY) && !e.getKey().equals("error") && !e.getKey().equals("attempts")) {
                record.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("error", String.valueOf(error));
        meta.put("attempts", Math.max(priorAttempts, attempts));
        record.put(DEADLETTER_KEY, meta);
        atomicWriteJson(path, record); // temp+fsync+replace, all within deadletter/
        return true;
    }

    /** Delete a dead-letter record (after a successful re-drive). Idempotent. */
    public boolean removeDeadLetter(String changeId) {
        String name = findDeadletterFile(changeId);
        if (name == null) {
            return false;
        }
        try {
            boolean deleted = Files.deleteIfExists(deadletterDir.resolve(name));
            fsyncDir(deadletterDir);
            return deleted;
        } catch (IOException exc) {
            return false;
        }
    }

    // ── crash-safe writes ───────────────────────────────────────────────────

    private void atomicWriteJson(Path path, Map<String, Object> obj) {
        Path dir = path.getParent();
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, ".tmp_", ".json");
        } catch (IOException exc) {
            throw new RuntimeException("could not create temp buffer file: " + exc.getMessage(), exc);
        }
        try {
            byte[] data = Json.write(obj).getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                OutputStream out = Channels.newOutputStream(ch);
                out.write(data);
                out.flush();
                ch.force(true);
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exc) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort
            }
            throw new RuntimeException("could not write buffer file " + path + ": " + exc.getMessage(), exc);
        }
        fsyncDir(dir);
    }

    private void writeSeqInt(long value) {
        Path dir = seqPath.getParent();
        Path tmp;
        try {
            tmp = Files.createTempFile(dir, ".tmp_seq_", "");
        } catch (IOException exc) {
            throw new RuntimeException("could not create temp seq file: " + exc.getMessage(), exc);
        }
        try {
            byte[] data = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                OutputStream out = Channels.newOutputStream(ch);
                out.write(data);
                out.flush();
                ch.force(true);
            }
            Files.move(tmp, seqPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exc) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best-effort
            }
            throw new RuntimeException("could not write seq file: " + exc.getMessage(), exc);
        }
        fsyncDir(dir);
    }

    private static void fsyncDir(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException ignored) {
            // Some platforms can't fsync a directory fd; the file fsync + atomic
            // rename still give the core durability guarantee.
        }
    }

    private static String sanitizeId(Object changeId) {
        String s = changeId != null ? String.valueOf(changeId) : "noid";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((Character.isLetterOrDigit(c) || c == '-' || c == '_') ? c : '_');
        }
        return sb.length() == 0 ? "noid" : sb.toString();
    }

    private static Long seqOf(String name) {
        int idx = name.indexOf('_');
        String head = idx == -1 ? name : name.substring(0, idx);
        try {
            return Long.parseLong(head);
        } catch (NumberFormatException exc) {
            return null;
        }
    }
}
