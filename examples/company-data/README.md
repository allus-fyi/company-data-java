# Company-data example — connections / fields / changes / webhooks / documents (Java SDK)

A runnable website that demonstrates **every regular company-data scenario** of
the allme platform — reading connected people's decrypted values, your
request-field catalog, the change feed, inbound webhooks, and creating
documents/contracts — through the `fyi.allme.allus:company-data` **Java SDK**. It
is modelled on the PHP reference example: ~90 % of the logic is a shared frontend
fetched from a pinned release; this directory is the thin Java backend that
implements the
[demo-backend contract](https://github.com/allme-sdk/example-test-suite) v3
(`CONTRACT.md`).

Every handler goes through the SDK's **intended top-level functions** on the
SERVICE-role data `Client` (never internals, never raw platform HTTP). There is
**no OAuth/OIDC leg** here — company-data has no consent step, so (unlike the
identity example) no third-party OIDC library is needed and there is no
`/callback` or `/enroll`.

The server is the JDK's built-in `com.sun.net.httpserver.HttpServer` on a
**single-thread executor** (one worker — requests serialize, so there are no
locks and no burn-on-read).

---

## Run it

**Prerequisite (once):** clone this SDK's public repo and install the SDK into
your local Maven repo so this example can resolve it:

```bash
git clone https://github.com/allus-fyi/company-data-java
cd company-data-java
mvn -q install -DskipTests
```

Then run this example:

```bash
cd examples/company-data
mvn -q compile exec:java
```

`exec:java` runs `Main`, which:

1. wipes `.runtime/` (fresh state every boot),
2. on first run, downloads the **pinned** frontend release named in
   `frontend.lock`, **verifies its sha256**, and unpacks it to
   `.frontend/<tag>/` (a present, verified bundle is a cache hit — nothing is
   re-fetched),
3. checks the bundle's `contract.json` version against the backend's,
4. refuses a busy port with a clear message, then
5. serves `http://localhost:8091` on a **single-thread executor**.

Open **http://localhost:8091** and pick a scenario. Each scenario's setup panel
has a **Save** button: it POSTs your settings to the backend, which writes them
to a canonical SDK **config file** (`.runtime/config/{sid}.json`, the service PEM
under `.runtime/config/keys/` at `0600`) — the same shape a real integrator wires
by hand. The panel shows the written path so you can open and read the real
config; **Run** then builds the SDK from that file (`Client.fromConfig`) and runs
off it. You still never hand-create or edit the file — the backend writes it from
your browser inputs; it is there to be read.

**Port.** `8091` is the default, overridable with the `PORT` env var:

```bash
PORT=8092 mvn -q compile exec:java
```

The default is deliberately the **same across all six SDK examples** (one browser
origin ⇒ your localStorage setup carries across SDKs) — the documented
consequence is that only one example runs at a time.

**Requirements:** JDK 21 and Maven on your `PATH`, plus
`curl` + `tar` on `PATH` (used to fetch/unpack the frontend bundle).

---

## Which SDK call implements each scenario

| id | Scenario | SDK call(s) | Start action |
|---|---|---|---|
| `companydata:read` | Read connected people's values, grouped by connection | `Client.connections()` | `data` (synchronous) |
| `companydata:definitions` | Your request-field catalog | `Client.requestFields()` | `data` |
| `companydata:changes` | Drain the change feed (crash-safe pump, idempotent on `Change.id`) | `Client.processChanges(handler)` | `data` |
| `companydata:webhook` | Public `POST /webhook` receiver + change-feed fallback | `Client.verifyWebhook` / `Client.parseWebhook`; each poll also `Client.drainBatch(500)` | `none` (accumulating) |
| `companydata:documents` | Create the six document / contract types | `Client.createDocument(...)` ×6 | `data` |

The four data scenarios run synchronously on `/start` and store a terminal
`done` run (read once via `GET /api/runs/{id}`). A build/handler failure — e.g.
an unreachable `api_url` — is stored as a **`failed`** run (never a 200 without a
result), so the shared client surfaces the error.

### The webhook scenario (accumulating)

`/start` persists a routing record `webhookId → runId` (superseding any prior
active webhook run) and returns `{action:{type:"none"}}` — there is **no
long-poll** (it would wedge the single worker). Events then arrive two ways:

- **`POST /webhook`** (the public inbound route) runs the exact
  read-id → `verifyWebhook` → `parseWebhook` sequence — **never** the combined
  `handleWebhook` (which can't split 401-vs-200). Unknown/stale id or no active
  run → **200** discard; a bad HMAC → **401**; a verified+parsed delivery →
  append + **200**; a verified-but-unparseable delivery → **200**
  acknowledge-and-note (`unparseable++`). Every accepted-and-dropped case is
  **200** because the platform delivery worker counts exactly 200 as success.
- **Change-feed fallback** (the always-works default): each `GET /api/runs` poll
  on the active run does ONE `Client.drainBatch(500)` fetch and appends new
  `source:"feed"` events deduped on `Change.id` (not `processChanges`, which
  loops the pump — that's the `companydata:changes` job).

The run stays `pending` while collecting; the frontend keeps polling and renders
the growing `run.result` `{webhookId, events, unparseable}`.

---

## Default target — the deployed AWS platform

The scenario setup inputs default to the deployed platform (owner decision
2026-07-24: pre-launch, the cluster is the test environment):

| Setup input | Default |
|---|---|
| API url | `https://api.allme.fyi` |

Register the demo's **service data client** (client id/secret + service key) in
the **allus portal at `portal.allus.fyi`**; each scenario's setup checklist names
the exact portal pages and any person-account prerequisites. The webhook
scenario's `webhookId` + secret and the documents scenario's target person
`share_code` are entered in their setup panels.

The `companydata:webhook` scenario is **setup-first**: register a webhook on your
service in the portal, then paste its **webhook id** and one-time **HMAC secret**
into the scenario before starting it — **the run refuses to start without them**
(`Server.java` answers `409 not_configured`). Set `encrypt_payload` OFF; this
example holds no account private key.

Once it is started it works **locally with no tunnel**: the **change-feed fallback
is the always-works default**, so events show up whether or not the platform can
reach you directly.

**Optional / advanced — real inbound delivery.** The scenario also exposes a
public route, `POST /webhook`, on this same port. Real platform webhook deliveries
reach it only when this backend is publicly addressable, e.g. via a tunnel:

```bash
cloudflared tunnel --url http://localhost:8091
```

Register the tunnel URL (plus `/webhook`) as the webhook's target in the portal.
This is entirely optional — skip it and the change-feed fallback still surfaces
every event.

---

## What's in here

| Path | What it is |
|---|---|
| `pom.xml` | This example's own Maven project — the SDK coordinate + Jackson. **Separate from the published SDK package.** |
| `src/main/java/fyi/allme/allus/companydataexample/Main.java` | The launcher (steps above). |
| `…/Server.java` | The backend: contract endpoints, config files + run stash, the five SDK handlers + the public webhook receiver. |
| `…/Runtime.java` | Cross-request state (config files, key PEMs, run stash, webhook route, pump cache), atomic writes, TTL sweep, clear. |
| `…/Json.java` | Tiny Jackson JSON helper. |
| `frontend.lock` | The pinned frontend release (`{tag, sha256}`). |
| `.frontend/` | The fetched, verified frontend bundle (git-ignored). |
| `.runtime/` | The written SDK config files + per-run state + pump cache, git-ignored, wiped every boot; `0700`. |

`.runtime/`, `.frontend/`, and `target/` are git-ignored.

---

## Bumping the frontend pin

The frontend ships as a checksummed release asset; the pin lives in
`frontend.lock` (`{"tag":"v0.3.0","sha256":"<sha256 of dist.tar.gz>"}`). To move
to a newer release: set `tag` + `sha256` from the release's
`shasum -a 256 dist.tar.gz`, `rm -rf .frontend/`, and run again — it downloads
the new tag, verifies the checksum, and checks the bundle's `contract.json`
version against the backend (a contract-version change means the backend must be
updated in the same step; the startup guard refuses a mismatch loudly).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| **`Could not resolve … company-data:0.0.13`** | Install the SDK first: `cd ../.. && mvn -q install -DskipTests`. |
| **`port 8091 is busy`** | Another example (or process) holds the port — only one runs at a time. Stop it, or `PORT=<n> … mvn … exec:java`. |
| **Stale / wrong frontend** after a pin bump | `rm -rf .frontend/` and run again to re-download the pinned release. |
| **`contract mismatch: …`** | The pinned bundle's `contract.json` version differs from what this backend implements. Bump `frontend.lock` to a matching release (and re-fetch), or update the backend. |
| **`frontend checksum MISMATCH`** | The downloaded `dist.tar.gz` doesn't match `frontend.lock`'s `sha256`. Fix the `sha256` or re-download; the example refuses an unverified bundle. |
| **A data scenario shows `failed` with an HTTP/transport error** | The `api_url` / credentials in the setup panel can't reach the platform — check them; the run correctly surfaces the error rather than a blank success. |
