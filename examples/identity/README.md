# Identity example — sign-in / OIDC / 2FA (Java SDK)

A runnable website that demonstrates **every identity scenario** of the allme
platform — Sign in with allme, OIDC login, and 2FA by allme — through the
`fyi.allme.allus:company-data` **Java SDK**. It is modelled on the PHP reference
example: ~90 % of the logic is a shared frontend fetched from a pinned release;
this directory is the thin Java backend that implements the
[demo-backend contract](https://github.com/allme-sdk/example-test-suite) (`CONTRACT.md`).

Every handler goes through the SDK's **intended top-level functions**
(`OAuthClient`, `Client`, `TwoFactorClient` — never internals, never raw platform
HTTP); the OIDC scenarios (5/6) use the standard third-party
[Nimbus `oauth2-oidc-sdk`](https://connect2id.com/products/nimbus-oauth-openid-connect-sdk)
client — that is the point of the OIDC demonstration (#314).

The server is the JDK's built-in `com.sun.net.httpserver.HttpServer` on a
**single-thread executor** (one worker — requests serialize, so there are no
locks and no burn-on-read).

---

## Run it — one command

**Prerequisite (once):** install the SDK into your local Maven repo so this
example can resolve it — the Maven analogue of the PHP example's path repo:

```bash
cd sdks/java
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q install -DskipTests
```

Then, from this directory:

```bash
cd examples/identity
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q compile exec:java
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
to a canonical SDK **config file** (`.runtime/config/{id}.json`, any PEM under
`.runtime/config/keys/` at `0600`) — the same shape a real integrator wires by
hand. The panel shows the written path so you can open and read the real config;
**Run** then builds the SDK from that file (`OAuthClient.fromConfig` /
`Client.fromConfig`) and runs off it. You still never hand-create or edit the
file — the backend writes it from your browser inputs; it is there to be read.

**Port.** `8091` is the default, overridable with the `PORT` env var:

```bash
PORT=8092 JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q compile exec:java
```

The default is deliberately the **same across all six SDK examples** (one browser
origin ⇒ your localStorage setup carries across SDKs) — the documented
consequence is that only one example runs at a time.

**Requirements:** JDK 21 (`JAVA_HOME=/opt/homebrew/opt/openjdk@21`), Maven, and
`curl` + `tar` on `PATH` (used to fetch/unpack the frontend bundle).

---

## Which SDK call implements each scenario

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

Detached / challenge polls are **short-cycled**: `GET /api/runs/{id}` does ONE
SDK wait with `timeout=2` (`pollResult` / `waitForResult`); the SDK's logical
"not completed within 2s" timeout leaves the run pending, a real transport
failure fails it.

---

## Default target — the deployed AWS platform

The scenario advanced inputs default to the deployed platform (owner decision
2026-07-24: pre-launch, the cluster is the test environment):

| Advanced input | Default |
|---|---|
| API url | `https://api.allme.fyi` |
| Authorize base | `https://web.allme.fyi/auth` |

Against these defaults OIDC discovery is correct as-is (the issuer is the API
url) and a phone reaches everything naturally — **no environment setup**. You
register the demo's OAuth apps / data clients in the **allus portal at
`portal.allus.fyi`**; each scenario's setup checklist names the exact portal
pages and any person-account prerequisites.

Register the redirect URI **`http://localhost:8091/callback`** on every OAuth app
you create (adjust the port if you set `PORT`).

> **Java-specific note — authorize base.** The Java SDK's public `OAuthClient`
> surface (`OAuthClient.fromConfig` / `new OAuthClient(config)`) uses the SDK's
> **default authorize base** (`https://web.allme.fyi/auth`, the deployed
> platform) — the authorize-base override is a package-private constructor, so a
> *non-default* authorize base entered in the browser is **not** applied by this
> example. The `api_url` (which drives OIDC discovery, the token exchange, and
> the 2FA/connections reads) IS honoured from the config file. Running against
> the deployed default therefore needs no change; a local stack works for every
> `api_url`-driven scenario (3/4/5/6/8) but the OAuth *consent* URL still points
> at the deployed authorize base.

---

## What's in here

| Path | What it is |
|---|---|
| `pom.xml` | This example's own Maven project — the SDK coordinate, the Nimbus OIDC library, Jackson. **Separate from the published SDK package.** |
| `src/main/java/fyi/allme/allus/identityexample/Main.java` | The one-command launcher (steps above). |
| `…/Server.java` | The backend: contract endpoints, config files + run stash, SDK + OIDC wiring. |
| `…/Runtime.java` | Cross-request state (config files, key PEMs, run stash), atomic writes, TTL sweep, clear. |
| `…/Pkce.java` | PKCE verifier + S256 challenge for the SDK OAuth scenarios. |
| `…/Json.java` | Tiny Jackson JSON helper. |
| `frontend.lock` | The pinned frontend release (`{tag, sha256}`). |
| `.frontend/` | The fetched, verified frontend bundle (git-ignored). |
| `.runtime/` | The written SDK config files + per-run state, git-ignored, wiped every boot; `0700`. |

`.runtime/`, `.frontend/`, and `target/` are git-ignored.

---

## Bumping the frontend pin

The frontend ships as a checksummed release asset; the pin lives in
`frontend.lock` (`{"tag":"v0.1.0","sha256":"<sha256 of dist.tar.gz>"}`). To move
to a newer release: set `tag` + `sha256` from the release's
`shasum -a 256 dist.tar.gz`, `rm -rf .frontend/`, and run again — it downloads
the new tag, verifies the checksum, and checks the bundle's `contract.json`
version against the backend (a contract-version change means the backend must be
updated in the same step; the startup guard refuses a mismatch loudly).

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| **`Could not resolve … company-data:0.0.13`** | Install the SDK first: `cd ../.. && JAVA_HOME=… mvn -q install -DskipTests`. |
| **`port 8091 is busy`** | Another example (or process) holds the port — only one runs at a time. Stop it, or `PORT=<n> … mvn … exec:java`. |
| **Stale / wrong frontend** after a pin bump | `rm -rf .frontend/` and run again to re-download the pinned release. |
| **`contract mismatch: …`** | The pinned bundle's `contract.json` version differs from what this backend implements. Bump `frontend.lock` to a matching release (and re-fetch), or update the backend. |
| **`frontend checksum MISMATCH`** | The downloaded `dist.tar.gz` doesn't match `frontend.lock`'s `sha256`. Fix the `sha256` or re-download; the example refuses an unverified bundle. |
