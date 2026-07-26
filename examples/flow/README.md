# Flow example — run a contract flow (Java SDK)

A runnable website that demonstrates a **contract flow** end-to-end through the
`fyi.allme.allus:company-data` **Java SDK**: trigger a flow run, drive the company
party through it with type-checked step filling, hand a turn to the person's phone,
and on completion read the decrypted answers and — for the contract fixture —
download the generated signed document. Like the
[identity example](../identity/), ~90 % of the logic is a shared frontend fetched
from a pinned release; this directory is the thin Java backend that implements the
[demo-backend contract](https://github.com/allme-sdk/example-test-suite)
(`CONTRACT.md`, flow family — contract v2).

Every handler goes through the SDK's **intended top-level flow functions** —
`identity()`, `triggerFlowRun()`, `flowRun()`, `processFlowRun()`,
`flowRunAnswers()`, `flowRunDocument()` — never internals, never raw platform HTTP.
The server is the JDK's built-in `com.sun.net.httpserver.HttpServer` on a
**single-thread executor** (one worker — requests serialize, so there are no locks).

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
cd examples/flow
mvn -q compile exec:java
```

`exec:java` runs `Main`, which wipes `.runtime/` (fresh state every boot), fetches +
**sha256-verifies** the pinned frontend release named in `frontend.lock` into
`.frontend/` (a present, verified bundle is a cache hit), checks the bundle's
`contract.json` version against the backend's, refuses a busy port with a clear
message, then serves `http://localhost:8091` — a **single-worker** server.

Open **http://localhost:8091** and pick the **Run a contract flow** scenario. From
there the browser and the allus portal are the only surfaces you touch. The
scenario's **Save** button POSTs your settings to the backend, which writes them to a
canonical SDK **config file** (`.runtime/config/{id}.json`, the service PEM under
`.runtime/config/keys/`) — the same shape a real integrator wires by hand. The panel
shows the written path; **Trigger** then builds the SDK from that file
(`Client.fromConfig`) and runs off it. You never hand-create or edit the file.

**Port.** `8091` is the default, overridable with `PORT` (e.g.
`PORT=8092 … mvn -q compile exec:java`). The default is deliberately the **same
across all six SDK examples** (one browser origin ⇒ your localStorage setup carries
across SDKs), so only one example runs at a time.

---

## The scenario — set up, then run

A contract flow is a company-authored graph of steps. The demo ships **two fixtures**
you import into the portal (`fixtures/`):

| Fixture zip | Shape |
|---|---|
| `fixtures/info-gathering.zip` | `data_only` — a few company steps (text, an **email** validation-demo step, an address composite) then one person turn. |
| `fixtures/contract.zip` | `document` — a company step, then a signature leaf that generates a document. |

The scenario's setup checklist names the exact portal steps. In short:

1. In the **allus portal**, register a **data client** (client_credentials) for the
   service — its whitelist auto-grants `/api/company-data/*`. Create/reuse the
   **service** and download its **private key (PEM)** (it decrypts the answers +
   document).
2. **Import** the chosen fixture zip (service settings → Flows → Import) and
   **publish** the imported flow.
3. In the browser, enter the data-client id/secret, pick the service PEM + its
   passphrase, enter the **published flow id** and the target **connection id**, and
   pick the same **fixture** you imported. **Save**, then **Trigger the flow run**.

What you then observe:

- The **flow-run log** accumulates one row per company step as the SDK drives it: the
  `email` step is submitted once with a bad value → rejected (the SDK's
  `ValidationError`, shown ✗), then re-submitted valid → accepted ✓. The other steps
  submit valid and advance.
- When the flow reaches the person's turn it shows **"waiting — answer on your
  phone"**; polling resumes automatically once the person answers (and, for the
  contract fixture, **signs**) in the allme app.
- On completion the **decrypted answers** appear, and for the contract fixture the
  **document** is downloaded via `flowRunDocument()`.
- **"What just happened"** lists the exact SDK methods the run called.

> **Phone required.** The person's turn — and the contract fixture's signature — are
> completed on a **physical phone** with the allme app, signed in as the connected
> demo person.

---

## Default target — the deployed AWS platform

The scenario's advanced input (**API url**) defaults to the deployed platform
(`https://api.allme.fyi`) — **no environment setup**. You register the data client,
create the service, and import + publish the flow in the **allus portal at
[portal.allus.fyi](https://portal.allus.fyi)** (the scenario's setup checklist
names the exact pages). A physical phone with the allme app reaches the deployed
platform naturally.

---

## Secondary target — a local stack

Running against a **local stack** is an optional secondary target. In the browser,
switch the advanced **API url** to `http://localhost:8070`; no file in **this**
example changes. The phone must be able to reach the local API.

---

## Bumping the frontend pin

The frontend ships as a checksummed release asset; the pin lives in `frontend.lock`
(`{tag, sha256}`). This example pins the **flow family bundle (contract v2)**. To move
to a newer release: note the release **tag** and its `dist.tar.gz` checksum
(`shasum -a 256 dist.tar.gz`) from `github.com/allme-sdk/example-test-suite`, set
`tag` + `sha256` in `frontend.lock`, `rm -rf .frontend/`, then run again.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| **`Could not resolve … company-data:…`** | Install the SDK first: `cd ../.. && mvn -q install -DskipTests`. |
| **`port 8091 is busy`** | Another example holds the port — only one runs at a time. Stop it, or `PORT=<n> … mvn … exec:java`. |
| **`contract mismatch: …`** | The pinned bundle's `contract.json` version differs from this backend (flow = v2). Bump `frontend.lock` to a matching release (and re-fetch), or update the backend. |
| **`frontend checksum MISMATCH`** | The downloaded `dist.tar.gz` doesn't match `frontend.lock`'s `sha256`. Fix the `sha256` or re-download. |
| **`start_failed: … private key`** | The service PEM / passphrase is wrong — re-pick the key and re-save. |
| **`start_failed`** naming a missing connection / person | The connection id is wrong or the person isn't connected to the service. |

---

## What's in here

| Path | What it is |
|---|---|
| `pom.xml` | This example's Maven project — the SDK from the local repo, nothing else. |
| `src/main/java/fyi/allme/allus/flowexample/Main.java` | The launcher. |
| `…/Server.java` | The `flow:run` handler (contract endpoints). |
| `…/Runtime.java` | Config files + run stash under `.runtime/` (wiped every boot). |
| `…/Json.java` | Minimal JSON read/write helper. |
| `fixtures/` | The two importable flow packages (portal-export zips). |
| `frontend.lock` | The pinned frontend release (`{tag, sha256}`). |
| `.frontend/` · `.runtime/` · `target/` | Git-ignored — the fetched bundle, runtime state, and build output never land in the repo. |
