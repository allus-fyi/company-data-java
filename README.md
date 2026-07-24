# allus-company-data (Java)

The Java SDK for the **allus company-data API**. Point it at a JSON config file
and it hands back typed, plaintext, **your-slug-keyed conclusions**: for each
connected person, a map of *your request-field slug → plaintext value* (plus
whether the value is live and when it last changed).

The SDK hides everything else — the OAuth token, the field catalog, the id
plumbing, the hybrid decryption, binary fetching, the changes-queue mechanics,
JSON-vs-XML. The platform is **zero-knowledge**: the API only ever holds
ciphertext, so all decryption happens inside the SDK with your service private
key. **The person's own field choices are never exposed** — you only ever see the
request slots you configured.

> This SDK is one of six language ports that share an identical API surface.
> This manual is the Java view of it.

**Contents:** [TL;DR — fetch new updates](#tldr--fetch-new-updates) ·
[Quickstart](#quickstart) · [Every call](#every-call) ·
[The typed value model](#the-typed-value-model) ·
[Company documents](#company-documents) ·
[The changes pump](#the-changes-pump) · [Webhooks](#webhooks) ·
[Rate limits](#rate-limits) · [Errors](#errors) ·
[How it's wired](#how-its-wired)

Deeper reference pages live in [`docs/`](docs/):
[config](docs/config.md) · [model](docs/model.md) · [pump](docs/pump.md) ·
[webhooks](docs/webhooks.md) · [errors](docs/errors.md).

---

## TL;DR — fetch new updates

```xml
<dependency>
  <groupId>fyi.allme.allus</groupId>
  <artifactId>company-data</artifactId>
  <version>0.0.5</version>
</dependency>
```

Point a config.json at your service keys:

```json
{
  "api_url": "https://api.allme.fyi",
  "client_id": "svc_xxx",
  "client_secret": "xxx",
  "service_private_key": "/path/to/service.pem",
  "key_passphrase": "xxx",
  "cache_dir": "./allus-cache"
}
```

Drain everything new, handled one update at a time:

```java
import fyi.allme.allus.companydata.Client;

Client client = Client.fromConfig("config.json");

client.processChanges(change -> {
    switch (change.event()) {
        case "field_updated" ->
            System.out.printf("%s %s = %s  (live=%s, at=%s)%n",
                change.personId(), change.slug(), change.value(), change.live(), change.at());
        case "field_deleted", "connection_deleted" ->
            System.out.printf("%s dropped %s%n", change.personId(), change.slug());
        default ->
            System.out.printf("%s %s%n", change.event(), change.personId());
    }
});
```

`processChanges` pulls every pending change, decrypts it, and hands them to your
callback ONE BY ONE, acking each only after your code returns. Crash mid-batch?
The next run replays exactly what wasn't acked — nothing is lost, and the API
keeps no backlog of its own. Run it on a schedule (cron / systemd timer); there
is no daemon/follow mode by design. Connections, binary values, and webhooks are
documented below.

---

## Quickstart

Requires **Java 21+**. Maven Central coordinates:

```xml
<dependency>
  <groupId>fyi.allme.allus</groupId>
  <artifactId>company-data</artifactId>
  <version>0.0.5</version>
</dependency>
```

Gradle:

```groovy
implementation 'fyi.allme.allus:company-data:0.0.5'
```

Everything is in the package `fyi.allme.allus.companydata`:

```java
import fyi.allme.allus.companydata.Client;
```

### 1. Write a config file

A single JSON file holds everything. Any field can be overridden by an `ALLUS_*`
env var, so secrets needn't live in the file. **No SDK method ever takes a key,
passphrase, or secret as an argument** — they all come from here.

`allus.json`:

```json
{
  "api_url": "https://api.allme.fyi",
  "client_id": "svc_1a2b3c…",
  "client_secret": "…",
  "service_private_key": "./service-CRM.pem",
  "key_passphrase": "…",

  "account_private_key": "./account.pem",
  "account_passphrase": "…",

  "webhooks": {
    "wh_abc123": "hmac_secret_for_that_webhook"
  },

  "cache_dir": "./allus-cache",
  "format": "json"
}
```

| Field | Required | Meaning |
|-------|----------|---------|
| `api_url` | yes | API base, e.g. `https://api.allme.fyi`. |
| `client_id` / `client_secret` | yes | The registered `client_credentials` credentials for **one** service. |
| `service_private_key` | yes | Path to the OpenSSL-encrypted PKCS#8 PEM you downloaded from the portal. |
| `key_passphrase` | yes | Decrypts that PEM in memory at startup. |
| `account_private_key` / `account_passphrase` | only for `encrypt_payload` webhooks | The company **account** key, used to unwrap an encrypted webhook envelope. |
| `webhooks` / `webhook_secret` | webhook auth — HMAC (default) | Per-webhook HMAC secrets keyed by webhook id (matched via the `X-Allus-Webhook-Id` header). A single-webhook service can use a flat `"webhook_secret": "…"` instead of the map. |
| `webhook_bearer_token` | webhook auth — bearer | Verify `Authorization: Bearer <token>` deliveries. |
| `webhook_basic` | webhook auth — basic | `{"username","password"}` — verify HTTP Basic deliveries. |
| `webhook_header` | webhook auth — header | `{"name","value"}` — verify a custom-header delivery. |
| `webhook_auth_none` | webhook auth — none | `true` — explicit opt-out; `verifyWebhook` always passes (use only behind your own gateway). **Configure at most one** webhook auth method (two+ → `ConfigError`). |
| `cache_dir` | no (default `./allus-cache`) | Durable local buffer for the changes pump. Must be writable + durable. |
| `format` | no (default `json`) | Wire format `json` or `xml`. Invisible in the output. |

Env overrides use the `ALLUS_` prefix of the field name, e.g.
`ALLUS_CLIENT_SECRET`, `ALLUS_KEY_PASSPHRASE`, `ALLUS_ACCOUNT_PASSPHRASE`,
`ALLUS_WEBHOOK_SECRET`. A missing/invalid config (or an unreadable PEM / wrong
passphrase) throws `ConfigException` at construction — fail fast.

### 2. First call — list a connection's values

```java
import fyi.allme.allus.companydata.Client;
import fyi.allme.allus.companydata.Connection;

Client client = Client.fromConfig("allus.json");

// Iterate every connected person (lazy, auto-paged).
for (Connection conn : client.connections()) {
    System.out.println(conn.displayName() + " " + conn.personId());
    conn.values().forEach((slug, val) ->
        System.out.printf("  %s = %s  (live=%s, updated=%s)%n",
            slug, val.value(), val.live(), val.updatedAt()));
    break; // just the first one for the demo
}
```

Or fetch one connection by id:

```java
Connection conn = client.connection("019xxxxxxxxxxxxxxxxxxxxxxxxx");
String email = conn.values().get("work_email").asString();   // "alice@acme.com"
```

`Client.fromEnv()` builds the same client entirely from `ALLUS_*` env vars (no file).

---

## Every call

`Client` is the only object you construct. Build it from config, then:

```java
Client.fromConfig(String path) -> Client    // from a JSON file (env overrides secrets)
Client.fromEnv()               -> Client     // entirely from ALLUS_* env vars
new Client(Config)             -> Client     // an already-built Config
new Client(Config, Transport)  -> Client     // inject an HTTP transport (tests / custom client)
```

### `requestFields()`

```java
List<RequestField> requestFields()
```

Your request-field **definitions** — fetched once from
`GET /api/company-data/request-fields` and cached for the life of the client (it
types every value). Returns *your* request config, never the person's fields.

* **Returns:** `List<RequestField>` — each `RequestField(slug, label, type, oneTime, mandatory, raw)`. `mandatory` is true when the field is mandatory-to-provide **or** mandatory-to-stay-connected.
* **Throws:** `AuthException`, `ApiException`, `RateLimitException`.

```java
for (RequestField f : client.requestFields()) {
    String flag = f.mandatory() ? "mandatory" : "optional";
    System.out.printf("%-20s %-10s %s%s%n", f.slug(), f.type(), flag, f.oneTime() ? " (one-time)" : "");
}
```

### `connections(limit, offset)`

```java
Iterable<Connection> connections()
Iterable<Connection> connections(int limit, int offset)
Stream<Connection>   connectionStream()
Stream<Connection>   connectionStream(int limit, int offset)
```

A **lazy iterable** that auto-pages `GET /api/company-data/connections?limit&offset`
and yields one typed `Connection` at a time (bounded memory for a large book).
Each `conn.values().get(slug)` is already decrypted (or a lazy binary handle). A
`Stream` view is also provided.

* **Params:** `limit` — page size (default 100); `offset` — starting offset.
* **Returns:** `Iterable<Connection>` / `Stream<Connection>`.
* **Throws:** `AuthException`, `ApiException`, `DecryptException` (per value, at access), `RateLimitException` (after the iterator's bounded internal backoff — see [Rate limits](#rate-limits)).

> **Heavily rate-limited.** Use for the initial full sync + occasional
> reconciliation only — never as a poll substitute for the changes feed. The
> iterator paces itself within the limit (backs off on `Retry-After`).

```java
// Initial full sync, streaming so a 100k-connection book never lands in memory.
for (Connection conn : client.connections(200, 0)) {
    upsertLocalRecord(conn);
}
```

### `connection(id)`

```java
Connection connection(String id)
```

Fetch one connection by its connection id (`GET /api/company-data/connections/{id}`).

* **Returns:** one `Connection`. Note: this endpoint returns `{connection_id, user_id, values}` and **no** `display_name`/`connected_at`, so those identity fields are `null` here (the list endpoint carries them).
* **Throws:** `AuthException`, `ApiException` (404 if unknown), `DecryptException`, `RateLimitException`.

### `logs(limit, offset)`

```java
List<LogEntry> logs()
List<LogEntry> logs(int limit, int offset)
```

The service's activity log (`GET /api/company-data/logs?limit&offset`) — **ops
events only** (email / purge / webhook), never person field data.

* **Returns:** `List<LogEntry>` — each `LogEntry(type, message, metadata, at, raw)`.

### `processChanges(handler, options?)`

```java
void processChanges(Consumer<Change> handler)
void processChanges(Consumer<Change> handler, Pump.Options options)
```

The crash-safe changes pump: drains the feed through `handler` **one `Change` at a
time**, durably buffering each batch before delivery, with per-item ack and retry
→ dead-letter → continue. Runs **until the feed is empty, then returns** — there is
**no follow/daemon mode** (you schedule re-runs yourself). Delivery is
**at-least-once**, so your handler **must be idempotent** (dedup on `Change.id()`).
See [The changes pump](#the-changes-pump).

* **Params:** `handler` — your callback; returning is an ack, throwing triggers retry.
* **Options:** `Pump.Options.defaults().withBatchSize(int)` (≤500), `.withMaxRetries(int)`, `.withOnError(Pump.OnError.DEADLETTER|HALT)`, `.withBackoff(IntFunction<Double>)`.
* **Throws:** `AuthException`, `ApiException`, `RateLimitException` (during a drain); whatever the handler throws if `onError=HALT` and retries are exhausted.

```java
client.processChanges(change -> {
    if (alreadyProcessed(change.id())) return;       // idempotency — dedup on the stable id
    switch (change.event()) {
        case "field_updated" -> store(change.personId(), change.slug(), change.value());
        case "connection_deleted", "field_deleted" -> remove(change.personId(), change.slug());
        default -> { }
    }
    markProcessed(change.id());
});
```

### Advanced changes primitives

```java
List<Change>               drainBatch(int max)                  // raw, UNBUFFERED — you own durability
List<Map<String, Object>>  deadLetters()                        // the local dead-letter store
int                        retryDeadLetters(Consumer<Change> h) // re-drive; returns count re-driven
int                        retryDeadLetters(Consumer<Change> h, Pump.Options options)
```

* `drainBatch(max)` — fetches one batch (clamped ≤ 500) and returns the decrypted `Change`s directly. It does **not** persist anything, so a crash loses what the API already deleted. Prefer `processChanges` for safe consumption.
* `deadLetters()` — each entry is the stored (ciphertext) event plus a flattened `error` and `attempts`.
* `retryDeadLetters(...)` — same options as `processChanges`; on success a record is removed, on repeated failure it stays dead-lettered (or re-throws under `HALT`). Dead letters are never re-fetched from the API — the local store is their only home.

### Key rotation — `key_rotated` and the public-key cache

Every client caches the RSA public keys it fetches: a person's key is immutable — until they
**rotate** it. A person learns of a rotation from a silent push; your service gets no pushes, so the
`key_rotated` change is your **only** signal. Without it a long-running worker keeps encrypting to
the rotated-away key for its whole lifetime, and the person can never read those values.

**On the pump this is automatic** — the cached key is dropped as the change passes through, before
your handler sees it. **Over a webhook it is not:** the signature verifier is static and has no
client instance, so it cannot reach the cache. Call the invalidator yourself — noting that the two
clients key their caches **differently**: the service client by `share_code`, the customer client by
the person's **user id**. Passing a share code to the customer client removes nothing and leaves you
encrypting to the old key. Both identifiers ride every change, alongside `public_key_sha256` — the
fingerprint of the person's new key.

```java
if ("key_rotated".equals(change.event())) {
    client.invalidatePublicKey(change.shareCode());   // service Client — keyed by SHARE CODE
    customer.invalidatePublicKey(change.personId());  // CustomerClient — keyed by PERSON USER ID
    // change.publicKeySha256() = fingerprint of the NEW key, if you want to verify the refetch
}
```

This is **eventual, not fail-closed** — nothing rejects a document encrypted to a stale key, so a
window remains between the rotation and your next drain. Drain often if that window matters.

### `service_key_rotated` — the same thing, the other way round

The customer client also caches the **service's** public key, the one you encrypt your consent
answers and documents *to*, keyed `"companyCode/serviceCode"`. When that company replaces its
service keypair, the `service_key_rotated` change on your account feed is your only signal — you
receive no pushes. Same shape, same guarantees, same automatic handling on the pump:

```java
if ("service_key_rotated".equals(change.event())) {
    // Automatic on the pump. Over a webhook, from the raw event body:
    customer.invalidateServiceKey(body.get("company_share_code"), body.get("service_share_code"));
    // body.get("service_public_key_sha256") = fingerprint of the service's NEW key
}
```

Also **eventual, not fail-closed**. Note the identifiers are **share codes**, not the ids used by
`invalidatePublicKey` — the two caches are keyed differently and the wrong call removes nothing.

### Webhook helpers (on the client)

```java
boolean verifyWebhook(Object rawBody, Map<String, ?> headers)
Change  parseWebhook(Object rawBody, Map<String, ?> headers)
Change  handleWebhook(Object rawBody, Map<String, ?> headers)   // verify + parse
```

`rawBody` is the raw request bytes (`byte[]`) or `String`; pass the **exact bytes**
the platform sent (don't re-serialize a parsed body). Fully config-driven — no
key/secret arguments. See [Webhooks](#webhooks).

---

## The typed value model

You work with these records and nothing else (`fyi.allme.allus.companydata`):

```text
RequestField { slug, label, type, oneTime, mandatory }      // YOUR request config
Connection   { id, personId, displayName, connectedAt, values: Map<slug, Value> }
Value        { value, live, updatedAt }
Change       { id, event, personId, slug?, value?, live?, at }
LogEntry     { type, message, metadata, at }
```

### Keyed by *your* slug

`conn.values().get("work_email").value()` → `"alice@acme.com"`. The key is the
stable, explicit slug you set per request field in the portal — rename the label
freely, the slug is the contract. **The person's source field is never exposed**:
no source slug, no `field_id`, not even via `.raw()`.

### `Value(value, live, updatedAt)`

| Accessor | Meaning |
|----------|---------|
| `value()` | The typed plaintext — `Object` (see the table below; `asString()`/`asObject()`/`asBinary()` are typed convenience casts). |
| `live()` | `true` if the person chose "keep connected" (auto-updates); `false` for a one-time snapshot. |
| `updatedAt()` | `OffsetDateTime` of when this answer last changed (per-answer, rides on the `Value`). |

### Value types (from the field's `type`)

| Field type | Java `value()` |
|------------|----------------|
| `email`, `phone`, `url`, `text` | `String` — `phone` is a single E.164-style string (`+` and digits) |
| `country`, `nationality` | `String` — an ISO 3166-1 alpha-2 code (e.g. `"US"`, `"NL"`); not a display name |
| `address`, `bank`, `creditcard` | `Map<String,Object>` — the decrypted plaintext is a JSON object, parsed for you |
| `date`, `date_of_birth` | `java.time.LocalDate` (falls back to the raw `String` if it can't be parsed) |
| `photo`, `document`, `legal_document` | a lazy `BinaryHandle` — see below |

`country`/`nationality` values are 2-letter ISO codes, and an `address`'s
`country`/`state` sub-fields are an ISO alpha-2 code / USPS 2-letter state code
respectively. `FieldValidation.isValid(type, value)` validates these against the
bundled country dataset; `FieldValidation.isValidCountryCode(code)` /
`FieldValidation.dialCodeFor(code)` check a code or look up its E.164 dial code.

```java
Map<String, Object> addr = conn.values().get("home_address").asObject();
LocalDate dob = (LocalDate) conn.values().get("birthday").value();
```

### Binary fields — the lazy `BinaryHandle`

A photo/document value is a `BinaryHandle`. Nothing is fetched or decrypted until
you call `.bytes()` or `.save(...)`:

```java
BinaryHandle handle = conn.values().get("passport_scan").asBinary();  // no network yet

byte[] data = handle.bytes();                          // GET the slot file → decrypt → file bytes
long n      = handle.save("/tmp/passport.jpg");        // same, written to disk; returns bytes written
String url  = handle.valueUrl();                        // the opaque slot-keyed URL it fetches from
```

`.bytes()` GETs the slot-keyed file endpoint, unwraps the API's
`{"encrypted": true, "value": <wrapper>}` envelope, decrypts with your service
key, parses the inner JSON envelope (`{"full": "data:…"}` for photos,
`{"file": "data:…"}` for documents) and base64-decodes the data URI into the file
bytes. The result is cached on the handle, so repeated calls don't re-fetch.
`.save(...)` writes atomically (temp file → fsync → atomic move).

### `Change(id, event, personId, slug?, value?, live?, at)`

A change-feed / webhook event.

| Accessor | Meaning |
|----------|---------|
| `id()` | **The stable server change-row id — your dedup key** (captured before the server delete). |
| `event()` | `connection_created`, `connection_deleted`, `field_updated`, `field_deleted`, `consent_accepted`, `consent_declined`. |
| `personId()` | The person the change is about (may be `null`). |
| `slug()`, `value()`, `live()` | Present only on `field_updated`; `value()` is typed exactly like `Value.value()` (incl. a lazy `BinaryHandle` for binaries). Connection/consent events carry no slot/value. `live()` is `null` when absent. |
| `at()` | `OffsetDateTime` of the change. (There is no separate `updatedAt` on a change.) |

### `.raw()`

Every model carries `.raw()` — the underlying *hardened* API object
(`Map<String,Object>`) — for debugging or an edge case the SDK didn't model. It
still never contains the person's source field.

See [`docs/model.md`](docs/model.md) for the full reference.

---

## Company documents

Beyond the per-person *values* a person shares with you, a service can also push
**documents** to its connections — a contract PDF, an offer, a structured JSON
payload. These are written by you (the company) and read by the person's app; the
SDK gives you the full create/list/get/update/delete surface.

The one rule to internalise: **every per-person document is automatically
end-to-end encrypted to the recipient's public key before it leaves the process —
for ANY value of `isPrivate`. Broadcast documents (no target) are sent plaintext.**
The SDK fetches the recipient key (`GET /api/keys/{shareCode}`) and encrypts for
you; **no method ever takes a key or secret argument**.

`isPrivate` is **device-display-only** — it tells the recipient's app whether to
show the document behind a lock (tap-to-reveal) or decrypt-on-load. It does **not**
decide whether the value is encrypted (the *target* does). Because a plaintext
broadcast can't be locked, `isPrivate=true` with no per-person target throws a
`ConfigException`.

Each document is `json` or `file` (`payloadKind`):

* `payloadKind="json"` → set `jsonValue(Object)` (any JSON-serialisable object).
* `payloadKind="file"` → set `fileBytes(byte[])` (and `fileMime(String)`).

### Create

```java
import fyi.allme.allus.companydata.Client;
import fyi.allme.allus.companydata.Client.CreateDocumentRequest;
import fyi.allme.allus.companydata.Document;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

Client client = Client.fromConfig("allus.json");

// BROADCAST — a plaintext json document for every connection of this service.
// No target ⇒ plaintext; isPrivate must stay false.
Document notice = client.createDocument(CreateDocumentRequest.builder()
    .kind("notice")
    .name("Q1 service update")
    .payloadKind("json")
    .jsonValue(Map.of(
        "headline", "New self-service portal",
        "effective", "2026-04-01"))
    .status("active"));

// PER-PERSON — automatically end-to-end encrypted to the recipient's public key.
// Target by connectionId / personUserId / shareCode (any one); the SDK resolves
// the recipient key. isPrivate here is display-only (lock vs decrypt-on-load).
byte[] pdf = Files.readAllBytes(Path.of("/tmp/contract-alice.pdf"));
Document contract = client.createDocument(CreateDocumentRequest.builder()
    .kind("contract")
    .name("Service agreement")
    .payloadKind("file")
    .fileBytes(pdf)
    .fileMime("application/pdf")
    .connectionId("019xxxxxxxxxxxxxxxxxxxxxxxxx")   // or .personUserId(...) / .shareCode(...)
    .isPrivate(true)                                 // recipient app shows it behind a lock
    .status("ready_to_sign"));

System.out.println(contract.id() + " " + contract.status());
```

`shareCode(...)` lets you skip the recipient-key lookup when you already have the
person's profile share code; otherwise the SDK resolves it from `connectionId`
(single-connection fetch) or `personUserId` (connections scan).

### List / fetch / update / delete

```java
// List this service's documents (paged; optional person/status filter).
for (Document doc : client.listDocuments()) {
    System.out.printf("%s  %-14s %-7s %s%n",
        doc.id(), doc.kind(), doc.status(), doc.name());
}
// Filtered: client.listDocuments(personUserId, status, limit, offset)
List<Document> openForAlice =
    client.listDocuments("019alice…", "ready_to_sign", 50, 0);

// Fetch one by id.
Document doc = client.document(contract.id());

// A per-person json document is an encrypted wrapper — .json() decrypts it
// transparently with your service key (a broadcast json doc returns plaintext as-is).
Object body = doc.json();        // throws DecryptException if it isn't a json doc

// Move it through its lifecycle: offering → ready_to_sign → active →
//                                active_but_ending → ended.
client.updateDocumentStatus(doc.id(), "active");

// Patch metadata / name / description (any subset; at least one required).
client.updateDocumentMetadata(doc.id(),
    Map.of("ref", "ACME-2026-014"),   // metadata
    "Service agreement (rev. 2)",     // name (or null)
    null);                            // description (or null)

// Remove it (and its on-disk file).
client.deleteDocument(doc.id());
```

`Document` carries `id()`, `kind()`, `name()`, `description()`, `status()`,
`payloadKind()`, `isPrivate()`, `value()`, `metadata()`, `createdAt()`,
`updatedAt()`, plus `.json()` (json docs) and `.raw()`.

### Reacting to status changes in the pump

When a document's lifecycle status changes, the platform emits a
`document_status_changed` change event. It rides the same pump / webhook channel
as field changes — the `Change` carries `documentId()` and `status()` (no
slot/value):

```java
client.processChanges(change -> {
    if (seen(change.id())) return;
    switch (change.event()) {
        case "document_status_changed" ->
            onDocumentStatus(change.documentId(), change.status());   // e.g. "active" / "ended"
        case "field_updated" ->
            store(change.personId(), change.slug(), change.value());
        default -> { }
    }
    recordSeen(change.id());
});
```

`documentId()` / `status()` are non-null only on `document_status_changed`.

---

## The changes pump

The changes feed is a server-side **drain-on-fetch queue**:
`GET /api/company-data/changes?limit=N` returns up to N events (default 100, max
500) **and deletes exactly those rows in the same transaction** — no
offset/cursor, and the API keeps no copy afterward. So consumption can't be a
plain list: a consumer crash mid-batch would lose events the API already deleted,
and a huge backlog must not materialize in memory. `processChanges` solves both.

**Per run, repeating until the feed is empty then returning:**

1. **Replay first.** Deliver any un-acked events already in the local buffer (from a previous crashed run), oldest-first.
2. **Drain.** When the buffer is empty, fetch one batch and **persist it to the durable file buffer (fsync) BEFORE handing anything out.** This is the backup the API no longer has.
3. **Deliver one-by-one.** For each buffered event, oldest-first: decrypt its value *at delivery* (never on disk), build the typed `Change`, call `handler`.
4. **Ack / retry / dead-letter.** On success, remove the event from the buffer (ack). On a handler error, retry with backoff up to `maxRetries`; then either move it to the dead-letter store and continue (`onError=DEADLETTER`, default — one poison event never wedges the stream) or stop and re-throw (`onError=HALT`). A `DecryptException` on a buffered event (corrupt/truncated ciphertext, rotated key) is **dead-lettered immediately** — re-decrypting can't fix it, so it does *not* burn retries (under `HALT` it re-throws). Either way it never propagates out and wedges replay.
5. Repeat until a drain returns empty **and** the buffer is drained → return.

### The durable buffer

* Plain files under `cache_dir` (zero extra dependencies): `pending/` for un-acked events, `deadletter/` for ones that exhausted retries.
* Stored events keep their **ciphertext** value — **no plaintext PII is ever written to disk**. Decryption happens only at delivery.
* Writes are crash-safe (temp file → `FileChannel.force(true)` fsync → `Files.move(ATOMIC_MOVE)` → directory fsync). Files are named with a monotonic, zero-padded sequence so they replay oldest-first.

### Crash safety, at-least-once, and idempotency

A batch is durably buffered *before* any delivery, and acked per-item only *after*
the handler succeeds. The ack can't be atomic with your side-effects — a crash
between your handler's success and its ack re-delivers that event on the next run.
That makes delivery **at-least-once**, so:

> **Your handler must be idempotent. Dedup on `Change.id()`.**

`Change.id()` is the stable server change-row id, captured before the server
delete, so it survives crash + replay unchanged.

### No follow mode

`processChanges` returns when the feed empties. **You** schedule re-runs — a cron
job, a `while (true) { client.processChanges(handle); Thread.sleep(5000); }` loop, a
worker queue, whatever fits. The feed is cheap to poll (see
[Rate limits](#rate-limits)).

### Worked example

```java
import fyi.allme.allus.companydata.Client;
import fyi.allme.allus.companydata.Pump;

Client client = Client.fromConfig("allus.json");

Runnable drain = () -> client.processChanges(change -> {
    if (seen(change.id())) return;                 // idempotent: skip already-applied
    switch (change.event()) {
        case "field_updated"  -> storeValue(change.personId(), change.slug(), change.value(), change.live());
        case "field_deleted"  -> clearValue(change.personId(), change.slug());
        case "connection_deleted" -> dropPerson(change.personId());
        default -> noteEvent(change.personId(), change.event(), change.at());
    }
    recordSeen(change.id());
}, Pump.Options.defaults().withBatchSize(200).withMaxRetries(5));

// Schedule your own re-runs; processChanges itself returns when empty.
while (true) {
    drain.run();
    Thread.sleep(5000);
}
```

If a handler keeps failing, the event lands in the dead-letter store instead of
blocking the stream; inspect with `client.deadLetters()` and re-drive with
`client.retryDeadLetters(handle)` after fixing the cause. See
[`docs/pump.md`](docs/pump.md).

---

## Webhooks

Webhooks are the lower-latency push alternative to polling the changes feed. The
platform POSTs each change event to your configured webhook URL with:

* `X-Allus-Webhook-Id` — which webhook this is (selects the HMAC secret from config).
* `X-Allus-Signature` — `HMAC-SHA256(rawBody, secret)` as lowercase hex.
* the body — the same slug-keyed `Change` shape as the pull feed (JSON or XML).

All secrets/keys come from config; the helpers take **no key or secret
arguments**. Use the raw request body bytes (do not re-serialize a parsed body —
the HMAC is over the exact bytes the platform sent).

### Delivery contract — effectively unique, rarely replayed

Each queued event is POSTed **once**, and **only HTTP `200` counts as delivered** — a
`202`, a `204`, a 3xx redirect and every 4xx/5xx are all treated as a failure. On anything
other than `200` (or a timeout or connection error) the event is **not retried in place**:
it and the rest of the webhook's queue move to a durable server-side backlog and the
webhook is marked bad. The backlog is delivered later, either automatically when
the webhook next probes healthy, or when you drain it yourself with
`GET /api/company-data/changes?webhook_id=…` (delete-on-read).

So deliveries are **effectively unique** — with one rare exception. If your endpoint
processed an event but the platform never saw your `200` (your response timed out, or
you crashed after committing but before responding), the event is treated as failed
and replayed on recovery, so you receive it **again**. Nothing caps that at two: a
failed probe leaves its backlog row in place, so every later recovery attempt whose
`200` is likewise lost replays the same event once more. Inside that window the
contract is **at-least-once** — plan for one or more repeats, not for exactly one.

> **Do not use `change.id()` as an idempotency key here.** On the webhook path the id is
> neither reliably stable nor reliably fresh, and a receiver cannot tell which one it is
> holding. A **live** delivery is built with no change row behind it, so its id is minted
> for that single POST — the later replay of the same event is rebuilt from a durable
> backlog row and therefore carries a **different** id. But a replayed delivery carries
> **that row's** id, and the row stays in place until it is delivered successfully, so a
> re-attempted replay arrives with the **same** id — which changes again if the event is
> re-backlogged after a further failure. An id check therefore misses the duplicate you
> are most likely to see and matches only a rarer one; it is not a contract. If you need
> strict idempotency, key on the **content** — event + person + slug/document + payload —
> never on the id.

**Webhooks and the pull feed are alternative integrations — consume one, never both.**
The id-dedup guidance in the changes-pump section above applies to the pump only, where
`change.id()` is the real server change-row id.


### In a Jakarta Servlet

```java
import jakarta.servlet.http.*;
import fyi.allme.allus.companydata.*;
import java.util.*;

public class AllusWebhookServlet extends HttpServlet {
    private final Client client = Client.fromConfig("allus.json");

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws java.io.IOException {
        byte[] body = req.getInputStream().readAllBytes();

        Map<String, String> headers = new HashMap<>();
        var names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String n = names.nextElement();
            headers.put(n, req.getHeader(n));
        }

        Change change;
        try {
            change = client.handleWebhook(body, headers);   // verify + parse
        } catch (WebhookException e) {
            resp.setStatus(401);                              // bad / unknown signature
            return;
        }

        applyChange(change);   // see "Delivery contract" above before adding dedup
        resp.setStatus(200);   // 200 — the ONLY status allus counts as delivered
    }
}
```

### With the JDK `HttpHandler` (`com.sun.net.httpserver`)

```java
import com.sun.net.httpserver.*;
import fyi.allme.allus.companydata.*;
import java.util.*;

HttpHandler handler = exchange -> {
    byte[] body = exchange.getRequestBody().readAllBytes();
    Map<String, List<String>> headers = exchange.getRequestHeaders(); // case-insensitive multimap
    try {
        Change change = client.handleWebhook(body, headers);
        applyChange(change);
        exchange.sendResponseHeaders(200, -1);
    } catch (WebhookException e) {
        exchange.sendResponseHeaders(401, -1);
    }
    exchange.close();
};
```

`verifyWebhook` / `parseWebhook` let you split the steps if you prefer:

```java
if (!client.verifyWebhook(body, headers)) { /* 401 */ }
Change change = client.parseWebhook(body, headers);
```

### Config-driven secrets

Per-webhook HMAC secrets live in the config `webhooks` map, keyed by webhook id;
the SDK reads `X-Allus-Webhook-Id` off the request and looks up the matching
secret. A single-webhook service can use the flat `"webhook_secret": "…"` shortcut
(or `ALLUS_WEBHOOK_SECRET`). An unknown/unconfigured id ⇒ verification returns
`false` (and `handleWebhook` throws `WebhookException`).

### The `encrypt_payload` account-key envelope

If a webhook has `encrypt_payload` enabled, the body is **replaced** by a
`{"_enc":1,…}` envelope encrypted to your company **account** key (and the HMAC is
over that envelope — the final bytes sent). `parseWebhook`/`handleWebhook` unwrap
it transparently using the configured `account_private_key` +
`account_passphrase`, then decrypt the inner field value with the service key — so
an encrypted-payload `Change` is identical to a plain one. If you receive such a
webhook without an `account_private_key` configured, you get a `WebhookException`.

> The account-key envelope uses OAEP-**SHA1** (OpenSSL's default), distinct from
> the OAEP-SHA256 used for person field values — the SDK handles this difference
> internally; you only supply the account key in config.

See [`docs/webhooks.md`](docs/webhooks.md).

---

## Rate limits

| Endpoint | Limit | Use it for |
|----------|-------|-----------|
| `changes` (the pump) | **generous** | Poll **as often as you like** — it's a cheap drain-on-fetch queue. |
| `request-fields`, `logs` | moderate | Occasional reads. |
| `connections`, `connection(id)`, binary `/file` | **heavily limited** | Initial full sync + occasional reconciliation **only** — never as a poll substitute. |

A 429 carries `Retry-After`. The SDK backs off and retries automatically:

* The transport retries a 429 a bounded number of times honoring `Retry-After`, then surfaces `RateLimitException`.
* The `connections(...)` iterator additionally backs off per `Retry-After` on a surfaced `RateLimitException` and retries the page a bounded number of times before re-throwing — so it paces itself within the limit instead of hammering.

If you catch a `RateLimitException`, its `.retryAfter()` is the seconds to wait
(or `null` when the header was absent).

---

## Errors

All from `fyi.allme.allus.companydata`. Same taxonomy + names (Java idiom) across
all six SDKs. They are unchecked (`RuntimeException`).

| Exception | When |
|-----------|------|
| `ConfigException` | Missing/invalid config, unreadable key file, or wrong passphrase — at construction (fail fast). |
| `AuthException` | Token fetch/refresh failed (bad `client_id`/`secret`, revoked client); or a 401 survives the one automatic refresh-and-retry. |
| `ApiException` | Any non-2xx from the API; carries `status()`, the platform `errorKey()` (when present), and `apiMessage()`. |
| `DecryptException` | A ciphertext wrapper is malformed, the key is wrong, or the GCM tag mismatches. Surfaces when a value is accessed/decrypted. |
| `WebhookException` | Signature verification failed, or an envelope couldn't be unwrapped/parsed. |
| `RateLimitException` | A 429 from a rate-limited endpoint. Subclass of `ApiException` (status fixed at 429); carries `retryAfter()` (seconds, or `null`). |

```java
import fyi.allme.allus.companydata.*;

try {
    Client client = Client.fromConfig("allus.json");
    for (Connection conn : client.connections()) { /* … */ }
} catch (ConfigException e) {
    // fix the config / key file
} catch (RateLimitException e) {
    wait(e.retryAfter() != null ? e.retryAfter() : 60);
} catch (ApiException e) {
    log(e.status(), e.errorKey(), e.apiMessage());
}
```

See [`docs/errors.md`](docs/errors.md).

---

## How it's wired

Everything below is what the SDK hides so your code only ever sees conclusions.

**Auth / token.** An internal HTTP layer owns a `client_credentials`-only token.
On the first call (or when the cached token nears expiry) it POSTs
`client_id`/`client_secret` to `{api_url}/oauth2/token` and caches the bearer
token + its expiry; refresh is automatic. A mid-flight 401 triggers exactly one
refresh-and-retry, then `AuthException`. The token is scoped server-side to **one**
service, so every call is implicitly that service's data. The transport is
pluggable (`new Client(config, transport)`); the default wraps
`java.net.http.HttpClient`.

**Slug resolution.** `requestFields()` is fetched once and cached; its slug→type
map types every value (so `address` parses to a `Map`, `photo` becomes a lazy
binary handle, etc.). The connection/changes endpoints return values keyed by
**your** request slug — the person's source field is dropped server-side and never
reaches the SDK.

**Decryption (zero-knowledge).** The service private key is loaded **once** at
construction from the configured encrypted PEM + passphrase into an in-memory RSA
key. A decrypt closure over it is handed to every model factory and the pump — the
key never appears in a method signature. Each value is a hybrid wrapper
(`{"_enc":1,"k":rsa_oaep_sha256(aesKey),"iv":…,"d":aes256gcm(…)}`); the SDK
RSA-OAEP-SHA256 unwraps the AES key (with an explicit `OAEPParameterSpec` pinning
MGF1 to SHA-256 — SunJCE would otherwise default it to SHA-1), then AES-256-GCM
decrypts the payload. The encrypted PEM is **PBES2** (PBKDF2-HMAC-SHA256 +
AES-256-CBC) and is read via **BouncyCastle**. **The platform only ever holds
ciphertext — it never sees your plaintext.**

**Binary fetch.** A binary value is a lazy `BinaryHandle` over a slot-keyed
`value_url`. On `.bytes()`/`.save()` it GETs that file endpoint, unwraps the
`{"encrypted":true,"value":<wrapper>}` envelope, runs the same service-key decrypt
to a JSON file-envelope, and base64-decodes its data URI to the file bytes.
(Slot-keyed, never source-field-keyed.)

**The drain-on-fetch feed.** `processChanges` delegates to a `Pump` wired to a
fetch closure (`GET /changes?limit=`, returning raw ciphertext events) and a
decrypt closure (builds a typed `Change`). Because the fetch deletes the rows it
returns, the pump persists each batch to the durable file buffer (ciphertext at
rest) before delivery, acks per-item after your handler succeeds, and replays the
buffer on restart — see [The changes pump](#the-changes-pump).

**XML.** When `format=xml`, responses + webhook bodies are parsed by a hardened
`DocumentBuilder` (DOCTYPE disallowed, external entities + entity-reference
expansion off, secure-processing on) — XXE-safe. The HMAC is always computed over
the raw bytes, never the parsed tree.

## Sign in with allme (OAuth, #195)

```java
OAuthClient oauth = OAuthClient.fromConfig("idw-config.json");
String url = oauth.authorizeUrl("signin", new OAuthClient.AuthorizeOptions().state(state).codeChallenge(ch));
// ...user approves; your redirect receives ?code=...
OAuthClient.SignInResult res = oauth.completeSignIn(code, verifier); // res.user(), res.mode(), res.values()
```

Modes: `signin` | `one_time` (claim values decrypted for you) | `connect` |
`2fa_enroll` (opt a person into 2FA — see below). `pollResult(state, timeout, interval)` drives the detached mode.

## 2FA by allme (#436, #481)

Ask a connected person to approve a login inside the allme app. On the same service data client (no new
config), via the `twoFactor()` sub-client:

```java
Client client = Client.fromConfig("allus.json");

// Raise a challenge. The idempotency key is REQUIRED — a repeat with the same key within the TTL returns
// the SAME challenge and sends no second push. The context is plain text shown on the person's card
// (pass null for none).
TwoFactorChallenge ch = client.twoFactor().challenge("2I6UF3", "login-8f3c1a", "Sign-in from Chrome");
if (ch.matchingDigits() != null) {                 // number matching is on for this service
    showOnLoginPage(ch.matchingDigits());          // the person types these back into the app; the server checks them
}

// Wait for the terminal outcome — polls result() for you (defaults: 600s timeout, 2s interval),
// throws ApiException on timeout.
TwoFactorResult res = client.twoFactor().waitForResult(ch.challengeId()); // or result(ch.challengeId()) to poll once yourself
if ("approved".equals(res.status())) {
    grantLogin();
}
```

`waitForResult(challengeId, timeoutSeconds, intervalSeconds)` overrides the default budget.

- **Burn-on-read.** The first read of a terminal state (`approved` | `denied` | `expired` | `revoked`)
  delivers it and burns it — a later read is `gone`. Read it once and persist your own outcome;
  `waitForResult` returns that first terminal read and never re-reads a consumed challenge.
- **Webhook variant.** The `2fa_challenge_completed` change/webhook carries the same terminal `status`, so a
  webhook consumer need not poll. **Expiry fires no webhook/Change** — only `approved`/`denied`/`revoked`
  reach the feed, so a lapsed challenge is observable only by polling.
- **Enrollment.** Only an enrolled person can be challenged (an un-enrolled `share_code` is `404`).
  Enrollment is a one-time consent on the `web.allme.fyi/auth` surface via the OAuth helper's `2fa_enroll`
  mode — a redirect button (`oauth.authorizeUrl("2fa_enroll", new OAuthClient.AuthorizeOptions().state(state))`),
  or server-to-server with `.responseMode("detached")` + `pollResult(state, 0, 0)`, which returns
  `{"enrolled": true, "state": ...}` once the person confirms.
- **Errors.** `404` (unknown / not-enrolled share code). A `429` is either the plain rate limit (retried with
  backoff → `RateLimitException`) or `twofa.pending_cap` (too many challenges already open for this person) —
  the latter surfaces immediately as `ApiException` and is never retried, since a retry cannot clear it.
