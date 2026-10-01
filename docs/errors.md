# Error model

Same taxonomy + names (Java idiom) across all six SDKs. All in
`fyi.allme.allus.companydata`, all unchecked (`RuntimeException`).

```java
import fyi.allme.allus.companydata.ConfigException;
import fyi.allme.allus.companydata.AuthException;
import fyi.allme.allus.companydata.ApiException;
import fyi.allme.allus.companydata.DecryptException;
import fyi.allme.allus.companydata.WebhookException;
import fyi.allme.allus.companydata.RateLimitException;
```

| Exception | Thrown when |
|-----------|-------------|
| `ConfigException` | Missing/invalid config, an unreadable key file, or a wrong passphrase — at construction (fail fast). |
| `AuthException` | The `client_credentials` token fetch/refresh failed (bad `client_id`/`secret`, revoked client); or a mid-flight 401 survived the one automatic refresh-and-retry. |
| `ApiException` | Any non-2xx from the API. |
| `DecryptException` | A ciphertext wrapper is malformed, the key is wrong, or the GCM tag mismatches. |
| `WebhookException` | Signature verification failed, or a webhook envelope couldn't be unwrapped/parsed. |
| `RateLimitException` | A 429 from a rate-limited endpoint. Subclass of `ApiException`. |

## `ApiException`

```java
class ApiException extends RuntimeException {
    int                 status();      // the HTTP status
    String              errorKey();    // the platform error_key, when the body provided one (else null)
    String              apiMessage();  // a human-readable message (else null)
    Map<String,Object>  details();     // the error body's remaining fields, verbatim (else empty)
}
```

`getMessage()` is `"HTTP <status> (<errorKey>): <message>"`. A transport failure
(no HTTP response — e.g. a connection error) surfaces as `ApiException` with
`status() == 0`.

`details()` is every field of the error body other than `error_key` / `error` /
`message`, so a response that carries actionable data beside its key is readable
without a bespoke exception type. The live example (#590) is a **410
`company_data.file_expired`** from a binary slot's file endpoint — a frozen
(Share-once) answer whose 90-day retention has elapsed:

```java
try {
    byte[] scan = conn.values().get("passport_scan").asBinary().bytes();
} catch (ApiException e) {
    if ("company_data.file_expired".equals(e.errorKey())) {
        // your archived copy is now the only one — and you can still prove what it is
        String sha       = (String) e.details().get("content_sha256");
        String expiredAt = (String) e.details().get("expired_at");
    }
}
```

A **421 `region.rebase_required`** never reaches you when the platform is reachable: it is the global front door telling the SDK to send the call to the caller's home region, which the SDK does automatically (README, **How it's wired** → Regions). It surfaces as `ApiException` in exactly three cases: the refusal's base is absent, not a string, or empty (nothing to rebase to); the refusal names the SDK's own current base (a self-referential directive, so rebasing would loop); or a rebase already happened once for this request and a second 421 still comes back. In every other case the SDK rebases and retries transparently.

## 503 `db.writes_paused` — saving is paused, retry

While the platform cannot complete a save in every region, a call can answer
**503** with `errorKey()` **`db.writes_paused`** (`"Saving data is not possible
right now"`) and the header `Retry-After: 30`. **Nothing was written**, so the call
is safe to repeat exactly as it was. Reads keep working.

It surfaces as a plain `ApiException` (`status() == 503`, `errorKey()` equal to
`"db.writes_paused"`); the SDK does not retry it. `ApiException` does not carry the
`Retry-After` header: wait 30 seconds, then repeat the same call.

Where it can come from:

* every company-data and customer call that is not a GET — creating, updating or
  deleting documents, flow-run starts, answers, uploads and generation, consent
  answers, connect requests, messages, 2FA challenges, `/api/keys/batch`;
* the change-feed drains `GET /api/company-data/changes` and
  `GET /api/customer/changes` (`processChanges`, `drainBatch`): nothing was
  drained, the events stay queued on the server and arrive on a later run, and the
  local buffer is untouched;
* `OAuthClient.pollResult` (`POST /oauth2/result`): the result is not consumed;
  poll again.

The token request (`POST /oauth2/token`) does not answer it: token grants keep
working while saving is paused.

```java
} catch (ApiException e) {
    if (e.status() == 503 && "db.writes_paused".equals(e.errorKey())) {
        Thread.sleep(30_000);
        // repeat the same call
    }
}
```

## 503 `platform.out_of_order` — the platform is out of order, retry

While the region serving a call is being rebuilt, the call answers **503** with
`errorKey()` **`platform.out_of_order`** (`"allme is temporarily out of order.
Please try again later."`) and the header `Retry-After: 300`. **The request was
not processed**, so the call is safe to repeat exactly as it was. The platform
answers normally again once the region is back in service.

It surfaces as a plain `ApiException` (`status() == 503`, `errorKey()` equal to
`"platform.out_of_order"`); the SDK does not retry it. `ApiException` does not
carry the `Retry-After` header: wait 300 seconds, then repeat the same call.

Where it can come from:

* every company-data and customer call, reads included — connections, request
  fields, binary fetches, documents, flow runs, consent answers, connect requests,
  messages, 2FA challenges and results, `/api/keys`;
* the change-feed drains `GET /api/company-data/changes` and
  `GET /api/customer/changes` (`processChanges`, `drainBatch`): nothing was
  drained, the events stay queued on the server and arrive on a later run, and the
  local buffer is untouched;
* every `OAuthClient` call — `exchangeCode`, `userinfo`, `pollResult` (the
  result is not consumed; poll again).

The `client_credentials` token request (`POST /oauth2/token`) the service and
customer clients make does not answer it, so the SDK still holds a token and the
503 arrives on the call itself. Every other grant at `POST /oauth2/token` — the
`OAuthClient` code exchange, a refresh-token grant — answers it.

```java
} catch (ApiException e) {
    if (e.status() == 503 && "platform.out_of_order".equals(e.errorKey())) {
        Thread.sleep(300_000);
        // repeat the same call
    }
}
```

## `RateLimitException`

```java
class RateLimitException extends ApiException {   // status() is always 429
    Double retryAfter();   // seconds from the Retry-After header, or null
}
```

The SDK already retries a 429 with backoff before surfacing this:

* the transport retries a bounded number of times honoring `Retry-After`;
* the `connections(...)` iterator additionally backs off + retries a page a bounded number of times.

For the heavily-limited connections endpoints it surfaces after that backoff so
you don't accidentally hammer them; on the changes feed it auto-backs-off within
reason. If you catch it, wait `e.retryAfter()` (or a default) before retrying.

## Where each surfaces

| Layer | Common exceptions |
|-------|-------------------|
| `Client.fromConfig` / `fromEnv` / `new Client(...)` | `ConfigException` |
| Token / any call (auth) | `AuthException` |
| `connections`, `connection`, `requestFields`, `logs`, pump drains | `ApiException`, `RateLimitException` |
| Value access / `BinaryHandle.bytes()` / pump delivery | `DecryptException`; `BinaryHandle.bytes()` also `ApiException` (it does the deferred GET — 410 `company_data.file_expired` when a frozen answer's retention has elapsed) |
| `verifyWebhook` / `parseWebhook` / `handleWebhook` | `WebhookException` (`verifyWebhook` returns `false` rather than throwing on a bad signature) |

## Example

```java
import fyi.allme.allus.companydata.*;

try {
    Client client = Client.fromConfig("allus.json");
    for (Connection conn : client.connections()) {
        process(conn);
    }
} catch (ConfigException e) {
    // fix the config / key file
} catch (AuthException e) {
    // bad/revoked credentials
} catch (RateLimitException e) {
    sleep(e.retryAfter() != null ? e.retryAfter() : 60);
} catch (DecryptException e) {
    // wrong service key or corrupt data
} catch (ApiException e) {
    log(e.status(), e.errorKey(), e.apiMessage());
}
```
