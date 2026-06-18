# The changes pump

The changes feed is a server-side **drain-on-fetch queue**:
`GET /api/company-data/changes?limit=N` returns up to N events (default 100, max
500) **and deletes exactly those rows in the same transaction**. There is no
offset/cursor/page, and the API keeps no copy after a fetch. So a consumer must:

* not lose a drained batch if it crashes mid-batch (the API already deleted it), and
* not materialize a huge backlog in memory.

`client.processChanges(handler)` (delegating to
`fyi.allme.allus.companydata.Pump`) does both.

## `processChanges(handler, options?)`

```java
void processChanges(Consumer<Change> handler);
void processChanges(Consumer<Change> handler, Pump.Options options);
```

`Pump.Options` is a record built fluently from `Pump.Options.defaults()`:

```java
Pump.Options.defaults()
    .withBatchSize(int)                 // clamped to [1, 500], default 100
    .withMaxRetries(int)                // default 3
    .withOnError(Pump.OnError.HALT)     // DEADLETTER (default) | HALT
    .withBackoff(attempt -> seconds)    // IntFunction<Double>, attempt(1-based) -> seconds
```

Drains the feed through `handler` one `Change` at a time, **until the feed is
empty, then returns**. No follow/daemon mode — schedule re-runs yourself.

## The cycle

1. **Replay first** — deliver any un-acked events already in the local buffer (a previous crashed run), oldest-first.
2. **Drain** — when the buffer is empty, fetch one batch (≤ `batchSize`, ≤ 500) and **persist it to the durable buffer (fsync) BEFORE handing anything out**.
3. **Deliver one-by-one** — for each buffered event, oldest-first: decrypt its value *at delivery* (never on disk), build the typed `Change`, call `handler.accept(change)`.
4. **Ack / retry / dead-letter** — on handler success, remove the event from the buffer (ack). On a handler exception, retry with `backoff` up to `maxRetries`; then:
   * `OnError.DEADLETTER` (default) → move it to the dead-letter store, log it, and continue (one poison event never wedges the stream);
   * `OnError.HALT` → re-throw the handler's exception (the event stays un-acked in the buffer for the next run).
   A **`DecryptException`** (corrupt/truncated ciphertext, rotated key) is special: the decrypt runs *inside* the delivery attempt, and an undecryptable event is **dead-lettered immediately** — re-decrypting can't fix it, so it does **not** burn `maxRetries`. Under `OnError.HALT` it re-throws like a handler error. Either way it never propagates out of `processChanges` and wedges step-1 replay.
5. Repeat until a drain returns empty **and** the buffer is drained → return.

## Crash safety · at-least-once · idempotency

A batch is durably buffered *before* any delivery, and acked per-item only *after*
the handler succeeds. A crash between a handler's success and its ack re-delivers
that event on the next run. Delivery is therefore **at-least-once**:

> **Your handler must be idempotent. Dedup on `Change.id()`** (the stable server
> change-row id, captured before the server delete).

## The durable buffer (on disk)

Under `cache_dir`:

```
<cache_dir>/pending/<seq>_<change_id>.json      # un-acked events, oldest-first
<cache_dir>/deadletter/<seq>_<change_id>.json   # events that exhausted retries
```

* Stored events keep their **ciphertext** `value`/`value_url` — **no plaintext PII is ever written to disk**. Decryption happens only at delivery.
* `<seq>` is a zero-padded, monotonically increasing sequence, so lexicographic filename order == oldest-first (stable even if `at` timestamps are equal/missing).
* Writes are crash-safe: temp file → `FileChannel.force(true)` fsync → `Files.move(ATOMIC_MOVE)` → dir fsync. A crash never leaves a half-written file.
* Re-instantiating the buffer on the same `cache_dir` recovers whatever is on disk — that recovery **is** the replay-on-restart.

## Options

| Option | Default | Meaning |
|--------|---------|---------|
| `batchSize` | 100 | Events per drain; clamped to `[1, 500]`. |
| `maxRetries` | 3 | Handler retries before dead-letter/halt. |
| `onError` | `DEADLETTER` | `DEADLETTER` (continue) or `HALT` (re-throw). |
| `backoff` | exponential, capped 30s | `attempt -> seconds` between retries. |

> Logging is wired via the `java.util.logging.Logger` you pass to the `Client`
> constructor (`new Client(config, http, logger, sleep)`); the default uses the
> `fyi.allme.allus.companydata.client`/`.pump` loggers. Every drain, deliver, ack,
> retry, dead-letter, and replay is logged.

## No follow mode — schedule re-runs

```java
while (true) {
    client.processChanges(handle);   // returns when the feed empties
    Thread.sleep(5000);              // the feed is cheap to poll (see rate limits)
}
```

A cron job, a worker loop, or any scheduler works equally well.

## Dead-letter inspect / re-drive

```java
List<Map<String, Object>> deadLetters();
int retryDeadLetters(Consumer<Change> handler);
int retryDeadLetters(Consumer<Change> handler, Pump.Options options);
```

* `deadLetters()` — each entry is the stored (ciphertext) event with a flattened `error` and `attempts`, plus its `id`.
* `retryDeadLetters(handler)` — re-drives every dead-lettered event through `handler`. On success the record is removed. On repeated failure (or a `DecryptException`) the dead-letter record is **updated in place** with the new error + attempt count and stays in `deadletter/` (`DEADLETTER`), or the error re-throws (`HALT`). The stored attempt count is monotonic across runs (`max(existing, new)`). Returns the count successfully re-driven.

A re-failing dead-letter never re-enters `pending/` — it is rewritten in place
within `deadletter/`, so a crash mid-re-drive can't resurrect it as a live event
on the next run. Dead letters are **never silently dropped** and **never
re-fetched from the API** (it already deleted them) — the local store is their
only home, which is exactly why it's durable.

```java
for (var dl : client.deadLetters()) {
    System.out.println(dl.get("id") + " " + dl.get("error") + " " + dl.get("attempts"));
}
int fixed = client.retryDeadLetters(handle);   // after fixing the handler bug
```

## Advanced: `drainBatch(max)`

```java
List<Change> drainBatch(int max);
```

A raw, **UNBUFFERED** drain: fetches one batch (clamped ≤ 500) and returns the
decrypted `Change`s directly — it does **not** persist anything to the buffer, so
**you own durability** if you use it (a crash loses what the API already deleted).
Prefer `processChanges` for safe consumption.
