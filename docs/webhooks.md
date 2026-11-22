# Events and webhooks

SmartBank tells other systems when money moves, using a **transactional outbox** and **signed webhooks** delivered at least once.

## Events

| Event | When |
| --- | --- |
| `transaction.posted` | A deposit, withdrawal or transfer posted (including the transfer created by posting a hold) |
| `hold.created` | A hold reserved money |
| `hold.posted` | A hold was posted, in full or in part |
| `hold.voided` | The payee voided a hold |
| `hold.expired` | A hold expired, through the expiry job or a late post attempt |

An endpoint receives an event when its owner owns one of the accounts involved (payer, payee or both) and it is subscribed to that event type. With no `eventTypes`, it is subscribed to all of them.

## Transactional outbox

The event row is written by [`OutboxWriter`](../backend/src/main/java/com/smartbank/events/OutboxWriter.java) **in the same database transaction** as the change it describes, together with one delivery row per subscribed endpoint. So:

- an event exists if and only if its change committed. A rolled-back transfer (for example, insufficient funds) has no event, and a committed one cannot lose its event, even across a database crash;
- a replayed request (same idempotency key) creates no second event;
- events are append-only: a database trigger rejects `UPDATE` and `DELETE`.

Reconciliation checks this continuously in the tests: every posted transaction has exactly one `transaction.posted` event, and every hold has one `hold.created` event plus one event for its final state. The concurrency, model-based and crash tests all end with this check.

The alternative this avoids is publishing after commit ("dual write"): if the process stops between the commit and the publish, the event is lost; publishing before commit can announce a transfer that then rolls back.

## Delivery

[`WebhookDispatcher`](../backend/src/main/java/com/smartbank/events/WebhookDispatcher.java) runs every second:

1. **Claim.** One `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)` statement leases up to 20 due deliveries for 60 seconds. Several app instances can run the worker without sending the same delivery twice at once.
2. **Send.** `POST` the event to the endpoint URL, outside any database transaction, with a 10-second timeout and no redirects.
3. **Record.** Any `2xx` marks it `DELIVERED`. Anything else (status, timeout, connection error) schedules a retry after `10 s × 2^(attempt−1)` plus up to 20% jitter. After 8 attempts (about 21 minutes in total) it becomes `FAILED`, a dead letter visible through the API.

If the worker dies mid-delivery, the lease expires and the delivery is retried. Delivery is therefore **at least once**: receivers must de-duplicate on the event id.

Settings: `smartbank.webhooks.poll-interval` (`PT1S`), `base-backoff` (`PT10S`), `max-attempts` (`8`), `request-timeout` (`PT10S`).

### Request

```http
POST /your/webhook HTTP/1.1
Content-Type: application/json
SmartBank-Signature: t=1763750400,v1=5f2b…
SmartBank-Event-Id: 6a1c…
SmartBank-Delivery-Id: 93e0…
SmartBank-Delivery-Attempt: 1

{"id":"6a1c…","type":"transaction.posted","createdAt":"2026-11-21T18:56:53.378419Z",
 "data":{"id":"43b1…","type":"TRANSFER","fromAccountId":"…","toAccountId":"…","amount":40.00,"currency":"CAD","createdAt":"…"}}
```

Every attempt for an event sends the same body; only the signature timestamp and attempt number change.

### Verifying the signature

`v1` is the hex HMAC-SHA256, keyed with the endpoint's secret, of `"<t>.<raw request body>"`. A receiver should:

1. recompute it over the raw bytes it received, before parsing the JSON;
2. compare in constant time;
3. reject requests whose `t` is more than a few minutes old, to block replays;
4. ignore event ids it has already processed.

## API

| Method | Path | Notes |
| --- | --- | --- |
| `POST` | `/api/v1/webhook-endpoints` | `{ "url", "eventTypes"? }`. Returns the endpoint **with its secret, shown only this once** |
| `GET` | `/api/v1/webhook-endpoints` | The caller's endpoints, without secrets |
| `DELETE` | `/api/v1/webhook-endpoints/{id}` | Deactivates it; queued deliveries are marked failed |
| `GET` | `/api/v1/webhook-endpoints/{id}/deliveries` | Newest 100 deliveries with status, attempts, last status code and error |
| `POST` | `/api/v1/webhook-endpoints/{id}/rotate-secret` | `?overlapSeconds=` (default 86400). Returns the new secret once |
| `POST` | `/api/v1/webhook-endpoints/{id}/deliveries/{deliveryId}/resend` | Queue one delivery again |
| `POST` | `/api/v1/webhook-endpoints/{id}/deliveries/resend-failed` | Queue every dead-lettered delivery again |

## Tests

[`WebhookIntegrationTest`](../backend/src/test/java/com/smartbank/events/WebhookIntegrationTest.java) runs a real HTTP receiver and checks:

- events only for committed changes, none for rollbacks or replays; the outbox is append-only;
- payer and payee endpoints receive a transfer, another user's endpoint does not; event-type filters apply;
- signatures verify with the endpoint secret and fail with a wrong secret or a tampered body;
- a receiver failing twice is retried on schedule (not before the backoff) and then succeeds, with the same event id and body each time;
- a receiver that always fails is dead-lettered after the maximum attempts, visible through the API;
- a receiver slower than the timeout is retried, not lost;
- a delivery leased by a worker that died is retried once the lease expires;
- six concurrent workers deliver 120 events exactly once each;
- a deactivated endpoint receives nothing more; the endpoint API validates URLs and event types and is private to its owner.

Checked against a planted bug: with withdrawals skipping their event, the model-based test (now the seeded simulation) failed and shrank the failure to a single withdrawal.

## Hardening

**SSRF protection** ([`WebhookUrlGuard`](../backend/src/main/java/com/smartbank/events/WebhookUrlGuard.java)). Without it, a user could register `http://169.254.169.254/…` (cloud metadata) or `http://localhost:5432` and make SmartBank send requests into its own network. A webhook URL must be `https`, carry no credentials, and every address its host resolves to must be public: not loopback, private, link-local, carrier-grade NAT, unique-local, multicast or unspecified, including IPv4 addresses embedded in IPv6 (mapped, compatible, 6to4, NAT64). The check runs at registration and **again before every delivery**, so a hostname that later starts resolving to an internal address (DNS rebinding) is caught; such deliveries fail with `Blocked: …` and no request is sent. Redirects are never followed. For local development, `WEBHOOK_ALLOW_HTTP` and `WEBHOOK_ALLOW_PRIVATE_TARGETS` relax this; both default to `false`.

**Encrypted secrets** ([`SecretCipher`](../backend/src/main/java/com/smartbank/events/SecretCipher.java)). Signing secrets are stored with AES-256-GCM under a key from `WEBHOOK_ENCRYPTION_KEY` (at least 32 characters, kept out of the database). Each ciphertext is bound to its endpoint's id, so copying it onto another endpoint's row makes it fail to decrypt. Secrets stored in plain text before this change are encrypted at startup.

**Secret rotation.** `POST /api/v1/webhook-endpoints/{id}/rotate-secret?overlapSeconds=86400` returns a new secret, once. Until the overlap ends, deliveries carry two signatures, `t=…,v1=<new>,v1=<old>`, so receivers that still know only the old secret keep accepting them; afterwards only the new secret signs. `overlapSeconds=0` stops the old secret at once, for a leaked secret.

**Resending.** After fixing a receiver, the owner can queue a dead-lettered delivery again (`POST …/deliveries/{deliveryId}/resend`) or all of them (`POST …/deliveries/resend-failed`). A resend starts a fresh series of attempts.

**Tests.** [`WebhookUrlGuardTest`](../backend/src/test/java/com/smartbank/events/WebhookUrlGuardTest.java) covers 33 URL cases with a fake DNS resolver. [`WebhookSsrfTest`](../backend/src/test/java/com/smartbank/events/WebhookSsrfTest.java) runs with the production defaults: internal URLs cannot be registered, and an endpoint whose host now resolves to 127.0.0.1 gets no request at all. With the delivery-time check removed, that test fails, because a request reaches the internal server. The webhook integration tests check that secrets are stored only encrypted and bound to their endpoint, that old plain-text secrets are migrated, that rotation signs with both secrets during the overlap and only the new one after, and that dead-lettered deliveries can be resent.

## Limits

- **DNS rebinding window.** The address check and the HTTP client's own DNS lookup are separate, so a host that changes its answer within that moment could slip through. Closing it fully needs an egress proxy or pinning the connection to the checked IP.
- **The encryption key** itself cannot be rotated yet (ciphertexts carry a `v1:` version prefix to allow it later), and lives in an environment variable rather than a key management service.
- **Ordering** is not guaranteed: a retry can arrive after a later event. Receivers should order by the event's `createdAt` or re-read state.
- **No pruning** of old events or deliveries.
- **Throughput impact** is not yet measured reliably. The outbox adds two SQL statements per posting, both outside the account-lock window; an A/B run was too noisy to quantify it (see [benchmarks](benchmarks.md)).
