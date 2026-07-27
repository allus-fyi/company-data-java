# allus Java SDK — example test suite

A runnable website that demonstrates **every scenario** of the allme / allus
platform through the `fyi.allme.allus:company-data` **Java SDK**, in three
families served together:

- **identity** — Sign in with allme, OIDC login, and 2FA by allme (scenarios 1–8);
- **flow** — run a company-authored **contract flow** end-to-end (`flow:run`);
- **company-data** — read connected people's decrypted values, your request-field
  catalog, the change feed, inbound **webhooks**, and creating **documents /
  contracts** (`companydata:*`).

~90 % of the logic is a shared frontend fetched from a pinned release; this
project is the thin Java backend that implements the
[demo-backend contract](https://github.com/allme-sdk/example-test-suite)
(**contract v3**). Every handler goes through the SDK's **intended top-level
functions** (`OAuthClient`, `Client`, `TwoFactorClient` — never internals, never
raw platform HTTP); the identity OIDC scenarios (5/6) additionally use the standard
[Nimbus `oauth2-oidc-sdk`](https://connect2id.com/products/nimbus-oauth-openid-connect-sdk)
client — that is the point of the OIDC demonstration.

The server is the JDK's built-in `com.sun.net.httpserver.HttpServer` on a
**single-thread executor** (one worker — requests serialize, so there are no
locks and no burn-on-read).

---

## Run it

**Prerequisites:** **JDK 21** and **Maven** on your `PATH`, plus `curl` + `tar`
(used to fetch and unpack the frontend bundle).

Clone the SDK, install it into your local Maven repository once (so this example
can resolve it — the Maven analogue of a path dependency), then start the suite:

```bash
git clone https://github.com/allus-fyi/company-data-java.git
cd company-data-java

# 1. install the SDK to your local Maven repo (once)
mvn -q install -DskipTests

# 2. start the example test suite
cd examples
mvn -q compile exec:java
```

`mvn compile exec:java` runs `Main`, which fetches the pinned portal bundle and
**serves the example test suite (all three scenario families) on
http://localhost:8091**. In detail, `Main`:

1. wipes `.runtime/` (fresh state every boot),
2. on first run, downloads the **pinned** frontend release named in
   `frontend.lock`, **verifies its sha256**, and unpacks it to `.frontend/<tag>/`
   (a present, verified bundle is a cache hit — nothing is re-fetched),
3. checks the bundle's `contract.json` version against the backend's (**v3**) and
   refuses a mismatch,
4. refuses a busy port with a clear message, then
5. serves `http://localhost:8091` on a single-thread executor.

Open **http://localhost:8091** and pick a scenario. Each scenario's setup panel
has a **Save** button: it POSTs your settings to the backend, which writes them to
a canonical SDK **config file** under `.runtime/config/` (any PEM under
`.runtime/config/keys/` at `0600`) — the same shape a real integrator wires by
hand. The panel shows the written path so you can open and read the real config;
**Run** (or **Trigger**) then builds the SDK from that file
(`OAuthClient.fromConfig` / `Client.fromConfig`) and runs off it. You never
hand-create or edit the file — the backend writes it from your browser inputs; it
is there to be read.

**Port.** `8091` is the default, overridable with the `PORT` env var:

```bash
PORT=8092 mvn -q compile exec:java
```

The default is deliberately the **same across all six SDK examples** (one browser
origin ⇒ your localStorage setup carries across SDKs) — the documented
consequence is that only one example runs at a time.

---

## Which SDK call implements each scenario

### identity — sign-in / OIDC / 2FA

| # | Scenario | SDK / OIDC-library call(s) |
|---|---|---|
| 1 | Sign in — redirect | `OAuthClient.authorizeUrl("signin", …)` → `/callback` → `OAuthClient.completeSignIn` |
| 2 | Sign in — detached | `OAuthClient.authorizeUrl("signin", …detached)` → `OAuthClient.pollResult` → `OAuthClient.completeSignIn` |
| 3 | One-time claims | `OAuthClient.authorizeUrl("one_time", claims…)` → `OAuthClient.completeSignIn` (decrypts values with the app private key from config) |
| 4 | Connect (stay-connected) | `OAuthClient.authorizeUrl("connect", …)` → `OAuthClient.completeSignIn`, then `Client.connections()` matched by `share_code` for LIVE values |
| 5 | OIDC login | Nimbus: `OIDCProviderMetadata.resolve` → `AuthenticationRequest` (PKCE) → `TokenRequest` (`client_secret_post`) → `IDTokenValidator.validate` |
| 6 | OIDC — continue on phone | same Nimbus flow; completion via the phone's redirect leg |
| 7 | 2FA at consent — **guide card** | no `/start`; a checklist + links to scenarios 1 and 5 |
| 8 | Standalone service-2FA + enrollment | `Client.twoFactor().challenge(...)` → `TwoFactorClient.waitForResult`; enroll runs `OAuthClient.authorizeUrl("2fa_enroll", …)` in the redirect & detached legs |

Detached / challenge polls are **short-cycled**: `GET /api/runs/{id}` does ONE SDK
wait with `timeout=2` (`pollResult` / `waitForResult`); the SDK's logical "not
completed within 2s" timeout leaves the run pending, a real transport failure
fails it.

> **Java-specific note — authorize base.** The Java SDK's public `OAuthClient`
> surface (`OAuthClient.fromConfig` / `new OAuthClient(config)`) uses the SDK's
> **default authorize base** (`https://web.allme.fyi/auth`, the deployed
> platform) — the authorize-base override is a package-private constructor, so a
> *non-default* authorize base entered in the browser is **not** applied by this
> example. The `api_url` (which drives OIDC discovery, the token exchange, and the
> 2FA/connections reads) IS honoured from the config file. Running against the
> deployed default therefore needs no change.

### flow — run a contract flow (`flow:run`)

Trigger a flow run and drive the company party through it with type-checked step
filling; hand a turn to the person's phone; on completion read the decrypted
answers and (for the contract fixture) download the generated document.

| Step | SDK call(s) |
|---|---|
| Trigger | `Client.identity()` (company party) + `Client.connection(id)` (customer party) → `Client.triggerFlowRun(...)` |
| Drive (per poll) | `Client.flowRun(id)`; if it's the company's turn `Client.processFlowRun(id, fill)` |
| Complete | `Client.flowRunAnswers(run)`; document mode also `Client.flowRunDocument(id)` |

The demo ships **two importable flow packages** under [`fixtures/`](fixtures/):

| Fixture zip | Shape |
|---|---|
| `fixtures/info-gathering.zip` | `data_only` — a few company steps (text, an **email** validation-demo step, an address composite) then one person turn. |
| `fixtures/contract.zip` | `document` — a company step, then a signature leaf that generates a document. |

Import the chosen fixture in the portal (service settings → Flows → Import) and
**publish** it, then enter the published flow id + target connection id in the
scenario setup. The `email` step is submitted once with a bad value → rejected
(the SDK's `ValidationException`, shown ✗), then re-submitted valid → accepted ✓.

> **Phone required.** The person's turn — and the contract fixture's signature —
> are completed on a **physical phone** with the allme app, signed in as the
> connected demo person.

### company-data — connections / fields / changes / webhooks / documents

| id | Scenario | SDK call(s) | Start action |
|---|---|---|---|
| `companydata:read` | Read connected people's values, grouped by connection | `Client.connections()` | `data` (synchronous) |
| `companydata:definitions` | Your request-field catalog | `Client.requestFields()` | `data` |
| `companydata:changes` | Drain the change feed (crash-safe pump, idempotent on `Change.id`) | `Client.processChanges(handler)` | `data` |
| `companydata:webhook` | Public `POST /webhook` receiver + change-feed fallback | `Client.verifyWebhook` / `Client.parseWebhook`; each poll also `Client.drainBatch(500)` | `none` (accumulating) |
| `companydata:documents` | Create the six document / contract types | `Client.createDocument(...)` ×6 | `data` |

The four data scenarios run synchronously on `/start` and store a terminal `done`
run (read once via `GET /api/runs/{id}`). A build/handler failure — e.g. an
unreachable `api_url` — is stored as a **`failed`** run (never a 200 without a
result), so the shared client surfaces the error.

**The webhook scenario is setup-first.** Its run needs the **registered webhook
id + HMAC secret** (entered in the setup panel) — register the webhook in the
portal first, then Save and Start. Events then arrive two ways:

- **`POST /webhook`** (the public inbound route on this same port) runs the exact
  read-id → `verifyWebhook` → `parseWebhook` sequence — never the combined
  `handleWebhook`. Unknown/stale id or no active run → **200** discard; a bad HMAC
  → **401**; a verified+parsed delivery → append + **200**; a
  verified-but-unparseable delivery → **200** acknowledge-and-note. Every
  accepted-and-dropped case is **200** because the platform delivery worker counts
  exactly 200 as success.
- **Change-feed fallback** (the always-works default): each `GET /api/runs` poll on
  the active run does ONE `Client.drainBatch(500)` fetch and appends new
  `source:"feed"` events deduped on `Change.id`.

Real platform webhook deliveries reach `POST /webhook` only when this backend is
publicly addressable — **a tunnel is OPTIONAL**; the change-feed fallback makes
the scenario work locally without one.

---

## Default target — the deployed platform, via the allus portal

The scenario setup inputs default to the deployed platform (`api_url =
https://api.allme.fyi`; identity's authorize base = `https://web.allme.fyi/auth`).
You register the demo's OAuth apps, data clients, services, flows, and webhooks in
the **allus portal at [`portal.allus.fyi`](https://portal.allus.fyi)**; each
scenario's setup checklist names the exact portal pages and any person-account
prerequisites.

For the identity scenarios, register the redirect URI
**`http://localhost:8091/callback`** on every OAuth app you create (adjust the
port if you set `PORT`).

A **local stack** is an optional secondary target: switch the advanced **API
url** to your local API in the browser — no file in this project changes (subject
to the identity authorize-base note above). A phone must be able to reach the API
you target.

---

## What's in here

| Path | What it is |
|---|---|
| `pom.xml` | This example's own Maven project — the SDK coordinate, the Nimbus OIDC library, Jackson. Not separately published, but its source ships **inside** the SDK jar at `examples/` (#493); its dependencies never become the SDK's. |
| `src/main/java/fyi/allme/allus/examples/Main.java` | The launcher (steps above). |
| `…/examples/Server.java` | The single router: contract endpoints, `/api/meta` aggregation, static bundle, dispatch to a family by scenario id. |
| `…/examples/Runtime.java` | Shared cross-request state (config files, key PEMs, run stash, webhook route, pump cache), atomic writes, TTL sweep, clear. |
| `…/examples/Http.java` · `…/examples/Util.java` · `…/examples/Json.java` | Shared HTTP plumbing, value/envelope helpers, and the tiny Jackson JSON helper. |
| `…/examples/identity/` | The identity scenario handlers (`IdentityHandlers`) + `Pkce`. |
| `…/examples/flow/` | The flow scenario handler (`FlowHandlers`). |
| `…/examples/companydata/` | The company-data scenario handlers (`CompanyDataHandlers`). |
| `fixtures/` | The two importable flow packages (portal-export zips). |
| `frontend.lock` | The pinned frontend release (`{tag, sha256}`) for the whole suite. |
| `.frontend/` · `.runtime/` · `target/` | Git-ignored — the fetched bundle, runtime state, and build output never land in the repo. |

To read a family's SDK usage, open its handler file under
`src/main/java/fyi/allme/allus/examples/<family>/` — the scaffolding (server,
runtime, plumbing) lives one package up and is shared by all three families.

---

## Bumping the frontend pin

The frontend ships as a checksummed release asset; the single pin lives in
`frontend.lock` (`{"tag":"v0.5.0","sha256":"<sha256 of dist.tar.gz>"}`). To move
to a newer release: set `tag` + `sha256` from the release's
`shasum -a 256 dist.tar.gz`, `rm -rf .frontend/`, and run again — it downloads the
new tag, verifies the checksum, and checks the bundle's `contract.json` version
against the backend (a contract-version change means the backend must be updated in
the same step; the startup guard refuses a mismatch loudly).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| **`Could not resolve … company-data:0.0.13`** | Install the SDK first, from the repo root: `mvn -q install -DskipTests`. |
| **`port 8091 is busy`** | Another example (or process) holds the port — only one runs at a time. Stop it, or `PORT=<n> mvn -q compile exec:java`. |
| **Stale / wrong frontend** after a pin bump | `rm -rf .frontend/` and run again to re-download the pinned release. |
| **`contract mismatch: …`** | The pinned bundle's `contract.json` version differs from what this backend implements (v3). Bump `frontend.lock` to a matching release (and re-fetch), or update the backend. |
| **`frontend checksum MISMATCH`** | The downloaded `dist.tar.gz` doesn't match `frontend.lock`'s `sha256`. Fix the `sha256` or re-download; the example refuses an unverified bundle. |
| **A data scenario shows `failed` with an HTTP/transport error** | The `api_url` / credentials in the setup panel can't reach the platform — check them; the run correctly surfaces the error rather than a blank success. |
| **`start_failed` (flow) naming a missing connection / key** | The connection id, service PEM, or passphrase is wrong — re-check them in the setup and Save again. |
