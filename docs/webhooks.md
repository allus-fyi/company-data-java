# Webhook receiver helpers

The lower-latency push alternative to polling the changes feed. The platform POSTs
each change event to your configured webhook URL with:

* `X-Allus-Webhook-Id` — which webhook this is (selects the HMAC secret from config).
* `X-Allus-Signature` — `HMAC-SHA256(rawBody, secret)` as lowercase hex.
* the body — the same slug-keyed `Change` shape as the pull feed (JSON or XML). If `encrypt_payload` is on, the body is replaced by a `{"_enc":1,…}` envelope encrypted to the company **account** key (and the HMAC is over that envelope).

**All secrets/keys come from config — these helpers take NO key or secret
arguments.** Always pass the **raw request body bytes** (`byte[]` or `String`;
don't re-serialize a parsed body — the HMAC is over the exact bytes sent).

## Client methods (the usual form)

```java
boolean verifyWebhook(Object rawBody, Map<String, ?> headers);
Change  parseWebhook(Object rawBody, Map<String, ?> headers);
Change  handleWebhook(Object rawBody, Map<String, ?> headers);   // verify + parse
```

`headers` is any `Map` whose keys are header names (case-insensitive lookup);
values may be `String` or `List<String>` (so the JDK `HttpExchange` header
multimap and a servlet's flattened map both work).

| Method | Returns | Errors |
|--------|---------|--------|
| `verifyWebhook` | `boolean` — recomputes `HMAC-SHA256(rawBody, secret)` and constant-time-compares (via `MessageDigest.isEqual`) to `X-Allus-Signature`. `false` on missing signature / unknown id / mismatch. | **Never throws** for a bad signature. |
| `parseWebhook` | a typed `Change`. Does **not** verify. Handles JSON, XML, and the `encrypt_payload` account-key envelope. | `WebhookException` on a malformed/unparseable body or envelope. |
| `handleWebhook` | a typed `Change` — verify **then** parse. | `WebhookException` on a bad/unknown signature, or any `parseWebhook` error. |

## Standalone functions

The same three are static methods on `Webhooks`. They take the `Config` and an
internal `ModelDeps` (the decrypt/type closures) explicitly — used by `Client`
internally; inside an app you'll normally use the client methods.

## In a web route

### Jakarta Servlet

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
        try {
            Change change = client.handleWebhook(body, headers);   // verify + parse
            if (!seen(change.id())) { applyChange(change); recordSeen(change.id()); }
            resp.setStatus(204);
        } catch (WebhookException e) {
            resp.setStatus(401);                                    // bad / unknown signature
        }
    }
}
```

### JDK `com.sun.net.httpserver.HttpHandler`

```java
import com.sun.net.httpserver.*;
import fyi.allme.allus.companydata.*;
import java.util.*;

HttpHandler handler = exchange -> {
    byte[] body = exchange.getRequestBody().readAllBytes();
    Map<String, List<String>> headers = exchange.getRequestHeaders(); // case-insensitive multimap
    try {
        Change change = client.handleWebhook(body, headers);
        if (!seen(change.id())) { applyChange(change); recordSeen(change.id()); }
        exchange.sendResponseHeaders(204, -1);
    } catch (WebhookException e) {
        exchange.sendResponseHeaders(401, -1);
    }
    exchange.close();
};
```

Split the steps if you prefer:

```java
if (!client.verifyWebhook(body, headers)) { /* 401 */ }
Change change = client.parseWebhook(body, headers);
```

## Config-driven secrets

Per-webhook HMAC secrets live in the config `webhooks` map, keyed by webhook id;
the SDK reads `X-Allus-Webhook-Id` and looks up the matching secret. A
single-webhook service can use the flat `"webhook_secret": "…"` shortcut (or
`ALLUS_WEBHOOK_SECRET`). An unknown/unconfigured id ⇒ `verifyWebhook` returns
`false` (and `handleWebhook` throws `WebhookException`).

## The `encrypt_payload` account-key envelope

If a webhook has `encrypt_payload` enabled, the whole body is a `{"_enc":1,…}`
envelope encrypted to your company **account** key, and the HMAC is over that
envelope. `parseWebhook`/`handleWebhook`:

1. Unwrap the envelope with the configured `account_private_key` + `account_passphrase` (loaded ONCE at client construction — no per-webhook PBKDF2).
2. Parse the inner payload (JSON or XML).
3. Decrypt the inner field `value` (a service-key wrapper) with the service key.

So an `encrypt_payload` `Change` is identical to a plain one. Receiving such a
webhook without an `account_private_key` configured throws `WebhookException`.

> The envelope uses RSA-OAEP-**SHA1** (OpenSSL's default MGF1-SHA1), distinct from
> the OAEP-SHA256 used for person field values. The SDK has two OAEP code paths
> and handles this difference internally — you only supply the account key in
> config.

## XXE safety

XML responses + webhook bodies are parsed by a hardened `DocumentBuilder`: DOCTYPE
declarations are disallowed, external general/parameter entities are off,
entity-reference expansion is off, and secure-processing is on. No external
DTD/entity is ever resolved. The HMAC is always computed over the raw bytes, never
the parsed tree.
