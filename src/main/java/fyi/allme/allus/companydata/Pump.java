package fyi.allme.allus.companydata;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntFunction;
import java.util.logging.Logger;

/**
 * Crash-safe streaming changes pump.
 *
 * <p>The changes feed is a server-side drain-on-fetch queue: a fetch returns up to
 * N events (default 100, max 500) and deletes those rows in the same transaction —
 * the API keeps no copy. So consumption cannot be a plain list: a consumer crash
 * mid-batch would lose events the API already deleted, and a huge backlog must not
 * materialize in memory. The pump solves both:
 *
 * <pre>
 * processChanges(handler) — one Change at a time, until the feed is empty, then
 *                           RETURNS. No follow/daemon mode (you schedule re-runs).
 * </pre>
 *
 * <p>Per cycle: replay the durable buffer first → drain one batch and persist it
 * (fsync) BEFORE delivery → deliver one-by-one (decrypt at delivery) → ack on
 * success / retry then dead-letter on failure / dead-letter a DecryptException
 * immediately → repeat until a drain is empty AND the buffer is drained.
 *
 * <p>Delivery is at-least-once (a crash between a handler's success and its ack
 * re-delivers on restart), so the handler MUST be idempotent — every {@link Change}
 * carries a stable {@code id} for dedup.
 *
 * <p>Injection (so tests + the real {@link Client} share one pump): the pump takes
 * a {@code fetchChanges(limit) -> List<Map>} source (raw ciphertext events) and a
 * {@code decrypt(event) -> Change} callable (closes over the loaded service key —
 * config-only key handling). No key/secret is ever a method argument.
 */
public final class Pump {
    /** The drain-on-fetch queue caps a fetch at 500. */
    public static final int MAX_BATCH = 500;
    private static final int DEFAULT_BATCH = 100;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final double DEFAULT_BACKOFF_S = 0.5;
    private static final double MAX_BACKOFF_S = 30.0;

    /** A fetch source: given a limit, drain-and-return up to that many raw event maps. */
    @FunctionalInterface
    public interface FetchChanges {
        List<Map<String, Object>> fetch(int limit);
    }

    /** A decrypt callable: raw event map → typed {@link Change} (value decrypted at delivery). */
    @FunctionalInterface
    public interface DecryptChange {
        Change decrypt(Map<String, Object> event);
    }

    /** Options for {@link #processChanges}. */
    public record Options(int batchSize, int maxRetries, OnError onError, IntFunction<Double> backoff) {
        public static Options defaults() {
            return new Options(DEFAULT_BATCH, DEFAULT_MAX_RETRIES, OnError.DEADLETTER, Pump::defaultBackoff);
        }

        public Options withBatchSize(int v) {
            return new Options(v, maxRetries, onError, backoff);
        }

        public Options withMaxRetries(int v) {
            return new Options(batchSize, v, onError, backoff);
        }

        public Options withOnError(OnError v) {
            return new Options(batchSize, maxRetries, v, backoff);
        }

        public Options withBackoff(IntFunction<Double> v) {
            return new Options(batchSize, maxRetries, onError, v);
        }
    }

    /** What to do when an event exhausts retries. */
    public enum OnError {
        /** Move it to the dead-letter store and continue (one poison never wedges the stream). */
        DEADLETTER,
        /** Stop the pump and re-raise. */
        HALT
    }

    private final FetchChanges fetchChanges;
    private final DecryptChange decrypt;
    private final DoubleConsumer sleep;
    private final Logger log;
    private final FileBuffer buffer;

    public Pump(Config config, FetchChanges fetchChanges, DecryptChange decrypt) {
        this(config, fetchChanges, decrypt, Pump::defaultSleep, Logger.getLogger("fyi.allme.allus.companydata.pump"));
    }

    public Pump(Config config, FetchChanges fetchChanges, DecryptChange decrypt,
                DoubleConsumer sleep, Logger log) {
        this.fetchChanges = fetchChanges;
        this.decrypt = decrypt;
        this.sleep = sleep;
        this.log = log;
        // The buffer recovers whatever is already on disk — that recovery IS the
        // replay-on-restart in step 1.
        this.buffer = new FileBuffer(config.cacheDir());
    }

    private static void defaultSleep(double seconds) {
        try {
            Thread.sleep((long) (Math.max(0.0, seconds) * 1000));
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }

    private static double defaultBackoff(int attempt) {
        return Math.min(DEFAULT_BACKOFF_S * Math.pow(2, attempt - 1), MAX_BACKOFF_S);
    }

    /** The durable buffer (for inspection / advanced use). */
    public FileBuffer buffer() {
        return buffer;
    }

    // ── the pump ──────────────────────────────────────────────────────────────

    /** Stream events through {@code handler} with default options. */
    public void processChanges(Consumer<Change> handler) {
        processChanges(handler, Options.defaults());
    }

    /**
     * Stream events through {@code handler} until the feed is empty, then return.
     *
     * <p>{@code handler} is called with one typed {@link Change} at a time and must
     * be idempotent (at-least-once; dedup on {@code Change.id}).
     */
    public void processChanges(Consumer<Change> handler, Options options) {
        int size = clampBatch(options.batchSize());

        while (true) {
            // 1. Replay anything already buffered (a previous crashed run), then
            //    deliver it. If the buffer is empty, drain ONE batch first.
            List<Map<String, Object>> pending = buffer.pending();
            if (!pending.isEmpty()) {
                log.fine("pump replay: " + pending.size() + " buffered event(s)");
            } else {
                int drained = drainIntoBuffer(size);
                if (drained == 0) {
                    return; // a drain returned empty AND the buffer is drained → done
                }
                pending = buffer.pending();
            }

            // 3+4. Deliver each buffered event oldest-first; ack/retry/dead-letter.
            for (Map<String, Object> event : pending) {
                deliverOne(event, handler, options);
            }
            // Loop: re-check the buffer (now drained) and try another drain.
        }
    }

    private int drainIntoBuffer(int size) {
        List<Map<String, Object>> batch = fetchChanges.fetch(size);
        if (batch == null) {
            batch = List.of();
        }
        log.fine("pump drain: fetched " + batch.size() + " event(s) (limit=" + size + ")");
        if (batch.isEmpty()) {
            return 0;
        }
        // Persist-before-deliver: the durable backup the API no longer has.
        buffer.append(batch);
        return batch.size();
    }

    private void deliverOne(Map<String, Object> event, Consumer<Change> handler, Options options) {
        String changeId = event.get("id") != null ? String.valueOf(event.get("id")) : null;
        int attempts = 0;

        while (true) {
            attempts++;
            try {
                // Decrypt only now — never on disk (ciphertext at rest). Inside the
                // try so a poison-ciphertext DecryptException is contained.
                Change change = decrypt.decrypt(event);
                handler.accept(change);
            } catch (DecryptException exc) {
                // A poison event: re-decrypting won't help, so don't burn retries.
                if (options.onError() == OnError.HALT) {
                    log.severe("pump halt: id=" + changeId + " undecryptable (" + exc.getMessage() + ")");
                    throw exc;
                }
                buffer.deadLetter(changeId, "DecryptException: " + exc.getMessage(), attempts);
                log.severe("pump dead-letter (undecryptable): id=" + changeId + ": " + exc.getMessage());
                return;
            } catch (RuntimeException exc) {
                if (attempts <= options.maxRetries()) {
                    double delay = Math.max(0.0, options.backoff().apply(attempts));
                    log.warning("pump retry: id=" + changeId + " attempt=" + attempts
                        + " failed (" + exc.getMessage() + "); backoff " + delay + "s");
                    if (delay > 0) {
                        sleep.accept(delay);
                    }
                    continue;
                }
                // Retries exhausted.
                if (options.onError() == OnError.HALT) {
                    log.severe("pump halt: id=" + changeId + " failed after " + attempts + " attempt(s)");
                    throw exc;
                }
                buffer.deadLetter(changeId, String.valueOf(exc.getMessage()), attempts);
                log.severe("pump dead-letter: id=" + changeId + " after " + attempts + " attempt(s): " + exc.getMessage());
                return;
            }
            // Success → per-item ack (remove from the buffer).
            buffer.ack(changeId);
            log.fine("pump ack: id=" + changeId);
            return;
        }
    }

    // ── advanced primitive ─────────────────────────────────────────────────────

    /**
     * Raw, UNBUFFERED drain → a list of typed {@link Change}s (advanced).
     *
     * <p>Fetches one batch (clamped ≤500) and returns the decrypted Changes
     * directly — it does NOT persist anything, so you own durability if you use it.
     * Prefer {@link #processChanges} for safe consumption.
     */
    public List<Change> drainBatch(int max) {
        int size = clampBatch(max);
        List<Map<String, Object>> batch = fetchChanges.fetch(size);
        if (batch == null) {
            batch = List.of();
        }
        log.fine("drainBatch: fetched " + batch.size() + " event(s) (limit=" + size + ")");
        return batch.stream().map(decrypt::decrypt).toList();
    }

    // ── dead-letter inspect / re-drive ─────────────────────────────────────────

    /** The local dead-letter store (ciphertext + error + attempt count). */
    public List<Map<String, Object>> deadLetters() {
        return buffer.deadLetters();
    }

    /** Re-drive every dead-lettered event through {@code handler} with default options. */
    public int retryDeadLetters(Consumer<Change> handler) {
        return retryDeadLetters(handler, Options.defaults());
    }

    /**
     * Re-drive every dead-lettered event through {@code handler}.
     *
     * <p>On success the dead-letter record is removed; on repeated failure it is
     * updated IN PLACE (DEADLETTER) — never routed back through pending/ — or the
     * error is re-raised (HALT). They are never re-fetched from the API. Returns the
     * count successfully re-driven.
     */
    public int retryDeadLetters(Consumer<Change> handler, Options options) {
        int redriven = 0;
        for (Map<String, Object> record : buffer.deadLetters()) {
            String changeId = record.get("id") != null ? String.valueOf(record.get("id")) : null;
            // Strip the reserved failure block before re-decrypting the event.
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, Object> e : record.entrySet()) {
                if (!e.getKey().equals("_deadletter") && !e.getKey().equals("error") && !e.getKey().equals("attempts")) {
                    event.put(e.getKey(), e.getValue());
                }
            }
            int attempts = 0;
            while (true) {
                attempts++;
                try {
                    // Decrypt inside the loop so an undecryptable dead-letter is
                    // contained here too — it updates its own record in place.
                    Change change = decrypt.decrypt(event);
                    handler.accept(change);
                } catch (DecryptException exc) {
                    if (options.onError() == OnError.HALT) {
                        log.severe("retryDeadLetters halt: id=" + changeId + " undecryptable (" + exc.getMessage() + ")");
                        throw exc;
                    }
                    buffer.updateDeadLetter(changeId, "DecryptException: " + exc.getMessage(), attempts);
                    log.warning("retryDeadLetters: id=" + changeId + " still undecryptable (" + exc.getMessage() + ")");
                    break;
                } catch (RuntimeException exc) {
                    if (attempts <= options.maxRetries()) {
                        double delay = Math.max(0.0, options.backoff().apply(attempts));
                        if (delay > 0) {
                            sleep.accept(delay);
                        }
                        continue;
                    }
                    if (options.onError() == OnError.HALT) {
                        log.severe("retryDeadLetters halt: id=" + changeId + " failed again");
                        throw exc;
                    }
                    // Refresh the stored attempt count + error IN PLACE — the record
                    // stays in deadletter/ and never re-enters pending/.
                    buffer.updateDeadLetter(changeId, String.valueOf(exc.getMessage()), attempts);
                    log.warning("retryDeadLetters: id=" + changeId + " still failing (" + exc.getMessage() + ")");
                    break;
                }
                buffer.removeDeadLetter(changeId);
                log.info("retryDeadLetters: id=" + changeId + " re-driven OK");
                redriven++;
                break;
            }
        }
        return redriven;
    }

    private static int clampBatch(int value) {
        int v = value;
        if (v < 1) {
            v = 1;
        }
        if (v > MAX_BATCH) {
            v = MAX_BATCH;
        }
        return v;
    }
}
