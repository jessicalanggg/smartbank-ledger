# API reference


All endpoints except health, login and register need `Authorization: Bearer <token>`. Money-moving `POST`s (deposits, withdrawals, transfers, linked transfers, reversals, holds) also need an `Idempotency-Key` header, or a `requestKey` body field, of up to 255 characters.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/actuator/health` | Public |
| `POST` | `/api/v1/auth/register` | Public. New users own no accounts yet |
| `POST` | `/api/v1/auth/login` | Public. Returns `accessToken` |
| `GET` | `/api/v1/accounts` | Accounts owned by the caller |
| `GET` | `/api/v1/accounts/{id}` | Owner only |
| `POST` | `/api/v1/accounts/{id}/deposits` | `{ "amount": "100.00" }` |
| `POST` | `/api/v1/accounts/{id}/withdrawals` | `{ "amount": "25.00" }` |
| `POST` | `/api/v1/transfers` | `{ "fromAccountId", "toAccountId", "amount" }`. Caller must own the source |
| `POST` | `/api/v1/transfers/by-account-number` | `{ "fromAccountId", "toAccountNumber", "amount" }` |
| `GET` | `/api/v1/fx/rates` | Current rate for each currency pair |
| `POST` | `/api/v1/fx/rates` | `{ "baseCurrency", "quoteCurrency", "rate" }`: 1 base buys `rate` quote. Admin only |
| `POST` | `/api/v1/fx/quotes` | `{ "fromAccountId", "toAccountId", "amount" }`: locks the rate and both amounts for 60 s. Caller must own the source |
| `POST` | `/api/v1/fx/transfers` | `{ "quoteId" }` plus `Idempotency-Key`: executes the quote, once |
| `POST` | `/api/v1/accounts/{id}/fees` | `{ "amount" }`. Charges a fee, credited to fee revenue. Admin only |
| `GET` | `/api/v1/ledger/accounts` | Chart of accounts with balances; `?currency=`, `?asOf=`. Admin only |
| `GET` | `/api/v1/ledger/trial-balance` | Debits, credits, balances by type, and the accounting-equation check; `?currency=`, `?asOf=`. Admin only |
| `GET` | `/api/v1/ledger/transactions/{id}/journal` | A transaction's debit and credit lines. Admin only |
| `GET` | `/api/v1/ledger/transactions/{id}` | Who started the transaction and which seal covers it. Admin only |
| `GET` | `/api/v1/ledger/seals/head` | The latest seal; record its hash outside the database. Admin only |
| `GET` | `/api/v1/ledger/seals/public-keys` | The Ed25519 public keys that verify seal signatures, with their key ids. Admin only |
| `GET` | `/api/v1/ledger/seals/verify` | Recomputes the hash chain, checks every signature, and reports the first broken seal. Admin only |
| `POST` | `/api/v1/transactions/{id}/reversals` | Optional `{ "amount" }`; without it, whatever is not yet reversed. Transfers: payee or admin. Deposits and withdrawals: admin |
| `POST` | `/api/v1/transfers/linked` | `{ "transfers": [ { "fromAccountId", "toAccountId", "amount" }, … ] }`, 1–20 legs, all or nothing. Caller must own every source |
| `GET` | `/api/v1/accounts/{id}/transactions` | Newest 100, owner only |
| `GET` | `/api/v1/transactions/{id}/details` | The transaction, its debit and credit lines on the chart of accounts, and the seal covering it (`null` until sealed). Owner of either account, or admin |
| `POST` | `/api/v1/holds` | `{ "fromAccountId", "toAccountId", "amount", "expiresInSeconds"? }`. Caller must own the source |
| `POST` | `/api/v1/holds/by-account-number` | `{ "fromAccountId", "toAccountNumber", "amount", "expiresInSeconds"? }` |
| `GET` | `/api/v1/holds/{id}` | Payer or payee only |
| `POST` | `/api/v1/holds/{id}/post` | Optional `{ "amount" }` for a partial post. Payee only |
| `POST` | `/api/v1/holds/{id}/void` | Payee only |
| `GET` | `/api/v1/accounts/{id}/statement` | `?from=&to=` (ISO times, default the last 30 days, at most 366): opening balance, postings with running balance, closing balance. Owner only |
| `GET` | `/api/v1/accounts/{id}/holds` | Holds in or out of the account, newest 100, owner only |
| `POST` | `/api/v1/webhook-endpoints` | `{ "url", "eventTypes"? }`. Returns the signing secret once |
| `GET` | `/api/v1/webhook-endpoints` | The caller's endpoints |
| `DELETE` | `/api/v1/webhook-endpoints/{id}` | Deactivate |
| `GET` | `/api/v1/webhook-endpoints/{id}/deliveries` | Delivery status, attempts and errors |
| `POST` | `/api/v1/webhook-endpoints/{id}/rotate-secret` | New secret, old one still signs during `?overlapSeconds=` |
| `POST` | `/api/v1/webhook-endpoints/{id}/deliveries/{deliveryId}/resend` | Resend one delivery (`…/deliveries/resend-failed` for all failed) |

Account responses include `balance` (posted), `availableBalance`, `pendingDebits` and `pendingCredits`.

Errors use RFC 9457 problem details: 429 too many requests (with `Retry-After`), 400 invalid input or a missing idempotency key, 422 an idempotency key reused for a different request, 401 bad credentials, 403 not the owner, 404 unknown account or hold, 409 insufficient funds, conflicting key reuse, a hold that is already final or expired, or another business rule.

Example:

```bash
TOKEN=$(curl -s localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"demo.a@smartbank.local","password":"<demo password>"}' | jq -r .accessToken)

KEY=$(uuidgen)
curl -si localhost:8081/api/v1/transfers -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $KEY" \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountId":"11111111-1111-4111-8111-111111111111","toAccountId":"22222222-2222-4222-8222-222222222222","amount":"10.00"}'
```

Sending the same command again with the same `$KEY` returns the identical response with `Idempotent-Replayed: true` and does not move money twice.
