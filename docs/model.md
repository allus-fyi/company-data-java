# Output model reference

The conclusions — the only objects you work with, all in
`fyi.allme.allus.companydata`. They are Java `record`s; each carries `.raw()` (the
underlying hardened API object as `Map<String,Object>`; never contains the
person's source field).

## `RequestField`

Your request-field **definition** — your config, never the person's fields.
Returned by `client.requestFields()`.

```java
record RequestField(
    String  slug,       // the stable, company-set key — the contract for value access
    String  label,      // the human label (rename freely; the slug stays)
    String  type,       // email|phone|url|text|address|bank|creditcard|date|date_of_birth|photo|document|legal_document
    boolean oneTime,    // a one-time snapshot vs a live (auto-updating) answer
    boolean mandatory,  // mandatory-to-provide OR mandatory-to-stay-connected (the API's two flags, folded)
    Map<String, Object> raw
) {}
```

## `Connection`

A connected person — identity + the slug-keyed value map. No source field
anywhere; `values()` is keyed by **your** request slug.

```java
record Connection(
    String id,
    String personId,
    String displayName,            // null on connection(id) (the list endpoint carries it)
    OffsetDateTime connectedAt,    // likewise null on connection(id)
    Map<String, Value> values,     // {<your_slug>: Value}
    Map<String, Object> raw
) {}
```

```java
conn.values().get("work_email").value();   // "alice@acme.com"
conn.values().get("mobile");                 // null if the person didn't answer that slot
```

## `Value`

One answer for one of your request slots.

```java
record Value(
    Object value,                  // typed plaintext (see below)
    boolean live,                  // true = "keep connected" (auto-updates); false = one-time snapshot
    OffsetDateTime updatedAt,      // when this answer last changed
    Map<String, Object> raw
) {}
```

Typed convenience casts: `asString()`, `asObject()` (Map), `asBinary()`
(BinaryHandle).

### `value()` types (resolved from the field's `type`)

| Field type | Java `value()` | Notes |
|------------|----------------|-------|
| `email`, `phone`, `url`, `text` | `String` | The decrypted plaintext. |
| `address`, `bank`, `creditcard` | `Map<String,Object>` | The decrypted plaintext is a JSON object → parsed. A non-JSON structured value throws `DecryptException`. |
| `date`, `date_of_birth` | `java.time.LocalDate` | Parsed from ISO `YYYY-MM-DD` (the leading 10 chars); falls back to the raw `String` if unparseable. |
| `photo`, `document`, `legal_document` | `BinaryHandle` | Lazy — nothing fetched/decrypted until `.bytes()`/`.save()`. |
| unanswered / no value | `null` | The slot has no answer. |

## `BinaryHandle`

A lazy handle for a binary value. No network or decryption happens at construction.

```java
class BinaryHandle {
    String valueUrl();             // the opaque slot-keyed file URL (read-only; may be null)
    byte[] bytes();                // fetch (if needed) → decrypt → decoded primary file bytes
    long   save(Path path);        // write bytes() to path atomically; returns bytes written
    long   save(String path);
}
```

On first `.bytes()`/`.save()`:

1. GET the slot-keyed file endpoint → the API serves `{"encrypted": true, "value": <wrapper>}`.
2. Decrypt the inner `{"_enc":1,…}` wrapper with the service key → a JSON file-envelope string (`{"full": "data:…", "thumb": …}` for photos, `{"file": "data:…", …}` for documents).
3. Base64-decode the primary data URI (`full` for photos, `file` for documents) → the file bytes. Cached on the handle (repeated calls don't re-fetch).

`.save(...)` writes crash-safely (temp file → `FileChannel.force(true)` fsync →
`Files.move(ATOMIC_MOVE)`). An unanswered binary slot yields an empty handle;
calling `.bytes()` on it throws `DecryptException`.

## `Change`

A change-feed / webhook event. Returned by the pump (`processChanges`,
`drainBatch`) and the webhook helpers.

```java
record Change(
    String  id,            // the stable server change-row id — YOUR dedup key
    String  event,         // see the event table
    String  personId,
    String  slug,          // field_updated/field_deleted/consent_* only (else null)
    Object  value,         // field_updated only; typed exactly like Value.value() (else null)
    Boolean live,          // field_updated only (else null)
    OffsetDateTime at,     // the change time (no separate updatedAt on a change)
    Map<String, Object> raw
) {}
```

### Events

| `event()` | Carries |
|-----------|---------|
| `connection_created` | identity only (no slot/value) |
| `connection_deleted` | identity only (no slot/value) |
| `field_updated` | `slug` + decrypted `value` (+ `live`); binary → a lazy `BinaryHandle` |
| `field_deleted` | `slug`, no value |
| `consent_accepted` / `consent_declined` | `slug` |

`Change.id()` is captured before the server's drain-delete, so it survives a
crash + replay unchanged — dedup on it.

## `LogEntry`

A service activity-log entry — ops events only (email / purge / webhook), never
person field data.

```java
record LogEntry(
    String type,
    String message,
    Object metadata,
    OffsetDateTime at,
    Map<String, Object> raw
) {}
```

## `.raw()`

Every model has a `.raw()` accessor: the underlying (hardened) API object, for
debugging or an edge case the SDK didn't model. It never contains the person's
source field — the hardened API doesn't return it.
