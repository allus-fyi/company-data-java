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
    int    status();      // the HTTP status
    String errorKey();    // the platform error_key, when the body provided one (else null)
    String apiMessage();  // a human-readable message (else null)
}
```

`getMessage()` is `"HTTP <status> (<errorKey>): <message>"`. A transport failure
(no HTTP response — e.g. a connection error) surfaces as `ApiException` with
`status() == 0`.

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
| Value access / `BinaryHandle.bytes()` / pump delivery | `DecryptException` |
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
