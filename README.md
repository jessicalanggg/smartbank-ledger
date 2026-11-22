# SmartBank

A banking transfer API built with Java 21, Spring Boot and PostgreSQL. It focuses on making money movement correct under retries and concurrency, and comes with a React frontend.

**Educational simulation only.** All data is synthetic. Do not expose it to the internet or use real personal or financial data.

## What it does

- **Accounts, deposits, withdrawals and transfers** through a versioned REST API (`/api/v1`), with transaction history per account.
- **Double-entry ledger with a chart of accounts.** Every transaction posts balanced, append-only debit and credit lines to typed accounts (assets, liabilities, equity, revenue), each customer account being its own liability account; fees post to fee revenue. An administrator trial balance, as of any moment, checks debits = credits and Assets = Liabilities + Equity + Revenue − Expenses. PostgreSQL rejects at commit any transaction whose entries do not sum to zero, and rejects any `UPDATE` or `DELETE` of entries or transactions. A reconciliation check proves every cached account balance equals the sum of its entries.
- **Two-phase transfers (holds).** A payer places a hold that reserves money without moving it, the way a card authorization does. The payee then posts it (in full or in part, releasing the rest) or voids it, and unposted holds expire automatically. Each account reports a posted balance, an available balance and pending amounts, and PostgreSQL rejects any change that would make the available balance negative.
- **Transactional outbox and signed webhooks.** Every posting and hold change writes an event in the same database transaction, so an event exists if and only if its change committed. A worker delivers events to subscribed endpoints with HMAC-SHA256 signatures, at least once, with exponential backoff, a dead-letter state, and `SKIP LOCKED` leases so several instances can deliver safely. Webhook URLs are checked against SSRF (no internal or private addresses) at registration and before every delivery; signing secrets are encrypted at rest and can be rotated with an overlap; dead-lettered deliveries can be resent. See [docs/webhooks.md](docs/webhooks.md).
- **Linked transfers.** Up to 20 transfers that post together or not at all, such as "charge the customer, pay the merchant, take a fee". Funds are checked on each account's net change across the group, every leg gets its own ledger entries and event tagged with the group's `linkId`, and the whole group is written in one SQL statement.
- **Reversals.** Mistakes are corrected with a new `REVERSAL` posting that points at the original and moves money back along the same path, never by editing history. Partial reversals are allowed but never exceed the original in total, enforced by a database trigger that locks the original at commit. A payee can refund a transfer; only an administrator can reverse deposits and withdrawals.
- **Tamper-evident history and an audit trail.** Every transaction records who started it. A background job seals committed transactions into a SHA-256 hash chain (each seal covers its transactions, their entries and the previous seal), so any change to sealed history, even with the database's append-only triggers switched off, is detected. Reconciliation verifies the chain on every run.
- **Balance checkpoints and statements.** Each account's running debit and credit totals are checkpointed per day, so a balance at any moment is the latest checkpoint plus the few entries since. Customers get statements with an opening balance, every posting with a running balance, and a closing balance; the administrator trial balance reads through the same checkpoints. Reconciliation re-checks every checkpoint against the entries.
- **Multi-currency with FX.** Accounts in CAD, USD, EUR, GBP or JPY, with amounts limited to each currency's decimals (no ¥10.5). Converting is quote-then-execute: a quote locks the rate and both amounts for 60 seconds and can be used once. An FX transfer is one transaction whose entries balance within each currency through per-currency FX position accounts, and the database rejects any transaction that does not.
- **Atomic money movement.** Each operation runs in one `@Transactional` unit: balance updates, the transaction record and its ledger entries commit together or roll back together.
- **Idempotency keys, scoped per user.** Every deposit, withdrawal, transfer and hold takes an `Idempotency-Key` header (or the `requestKey` body field). The first response, success or business error, is stored and replayed byte-for-byte for retries, marked `Idempotent-Replayed: true`, even when many retries arrive at once. Reusing a key for a different request returns 422. Keys are scoped to the calling user and expire after 24 hours. Posting stays exactly-once even if the server stops between posting and storing the response.
- **Short, deadlock-free locking.** A transfer writes its transaction and ledger entries first, then updates both balances last with atomic statements in UUID order (the debit only succeeds if available funds cover it). Account locks are held only for those final statements and the commit, and opposing transfers (A→B and B→A) cannot deadlock.
- **Few database round trips.** A transfer writes its transaction, ledger entries, outbox event and webhook deliveries in one SQL statement and both balance updates in one batch: 6 round trips instead of 17, measured with injected network delay ([docs/benchmarks.md](docs/benchmarks.md#round-trips-per-operation)).
- **Batched posting (group commit).** Concurrent transfers, deposits, withdrawals and linked transfers are posted together: one database transaction locks every account in the batch once, decides each posting in arrival order (a rejected one does not fail the others), and writes them all in one statement. With every transfer paying one account, that is about 5–6× the throughput and p99 latency under 80 ms instead of hundreds of milliseconds. Callers are answered only after commit. See [docs/benchmarks.md](docs/benchmarks.md#batched-posting-group-commit).
- **Benchmarked hot-account throughput.** When every transfer pays one account, this design sustains about 2.6× the throughput of locking both accounts for the whole transfer, with about 10× lower p99 latency (≈540 vs ≈200 transfers/s, p99 under 300 ms vs ≈3 s). See [docs/benchmarks.md](docs/benchmarks.md).
- **Rate limiting.** Token buckets in PostgreSQL, shared by every app instance, each checked with one atomic statement. Failed logins are limited per email (a successful login gives its token back), and login, registration and unauthenticated requests per IP; authenticated requests per user, with a stricter limit for writes. Refused requests get 429 with `Retry-After`. Unknown emails take as long to reject as wrong passwords, so login timing does not reveal which accounts exist.
- **Authentication and authorization.** Spring Security with stateless JWT (HS256, 30-minute expiry) and BCrypt password hashing. Users can only read, debit or view the history of accounts they own.
- **Database-enforced invariants.** Flyway migrations define `CHECK (balance >= 0)`, positive amounts, valid transaction shapes, a `UNIQUE` request key, balanced ledger postings and append-only history, so the database rejects bad states even if application code is wrong.
- **React frontend** with login, accounts (with available balance and pending amounts), history, deposits, withdrawals, transfers by account number, and holds: place a hold by account number, then capture incoming holds in full or in part, or release them. Every money-moving action retries safely with the same idempotency key.

## Verified by tests

All tests run against real PostgreSQL in Testcontainers, apart from the unit tests.

| Test | What it proves |
| --- | --- |
| 5,000 random concurrent transfers (32 threads, 10 accounts) | Total money is conserved, no balance goes negative, no deadlocks, exactly one row per successful transfer, and the ledger reconciles with every balance |
| 100 identical transfers fired at once | Posted exactly once; every caller gets the same transaction |
| 90 conflicting transfers sharing one key, fired at once | Posted at most once, although the requests lock different accounts |
| 100 identical deposits + 100 identical withdrawals fired at once | Each posted exactly once |
| 4,000 random holds, posts, voids, expiries and transfers (32 threads) | No deadlocks, no money created or lost, available balances never negative, pending amounts and posted holds reconcile |
| 10 posts racing 10 voids on each of 20 holds | Every hold ends posted or voided, never both; one transfer per posted hold |
| Reversal tests | Partial and full refunds by the payee; payer and strangers refused; deposits and withdrawals admin-only; refund needs funds in the payee's account; idempotent retries; reversals, opening balances and unknown ids refused; 40 concurrent partial refunds never exceed the original; the database rejects an over-reversal or a mis-routed one inserted directly |
| Batch tests | Deposits, withdrawals, transfers and linked groups share batches, decided in arrival order; a linked group that cannot be covered is rejected alone and writes no legs; keys shared across types; a rejected posting does not fail its batch; decisions follow arrival order; duplicate keys inside a batch post once; committed keys replay; the same key racing a batch and a single transfer posts once (the deadlock case); concurrent callers share batches |
| Hold tests | Reservation, full and partial posting, voids, expiry (including a post that arrives after expiry), idempotency, and database rules on hold state changes |
| Rate-limit tests | Failed logins lock an account while successes do not count; unknown emails behave like real ones; one address cannot spray many accounts; registrations per address; per-user API limits refill; writes have a stricter limit; 200 concurrent requests take exactly the 25 tokens that exist; two app instances share one budget; idle buckets are pruned |
| Idempotency API tests | Byte-identical replays, per-user scoping, 422 on a different request, stored business errors, keys not consumed by 403/400, expiry and pruning, recovery when the response was never stored, 30 simultaneous retries posting once |
| Linked transfer tests | Charge/pay/fee flow; one failing leg posts nothing; net funds checks; idempotent retries and 422 on key reuse; caller must own every source; validation. Plus 1,500 concurrent groups of 2–5 legs: every committed group has all its legs, rejected groups none, no deadlocks |
| Webhook hardening tests | 33 SSRF URL cases; internal URLs refused at registration; a host that now resolves internally gets no request; secrets stored only encrypted and bound to their endpoint; old plain-text secrets migrated; rotation signs with both secrets during the overlap; dead-lettered deliveries resent |
| Webhook tests (real HTTP receiver) | Events only for committed changes; routing to payer and payee only; signatures verify and reject tampering; retries on schedule; dead-lettering; timeouts; expired leases; 6 concurrent workers deliver 120 events exactly once |
| Hold API tests (real JWT security) | Only the payer can place a hold, only the payee can post or void it, only the two parties can see it |
| FX tests | A quote locks its rate even if the rate changes; execution posts four entries that balance within each currency, with balanced trial balances in both; rounding down to each currency's decimals (including JPY); quotes are single-use, expire and are private; paying another user's account; a failed execution leaves the quote usable; same-currency, missing-rate and plain cross-currency transfers rejected; JPY decimals rejected; rates are admin-only; 20 racing executions of one quote post once; the database rejects an FX transaction that does not balance per currency; FX details are covered by the hash chain |
| Checkpoint tests | Balances at many moments, through checkpoints, equal a full scan of the entries; the trial balance through checkpoints matches the entries; the job checkpoints each boundary once and only past the horizon; an entry committed after its checkpoint is reported; an altered checkpoint is reported; statements show opening, lines with running balance and closing, and are owner-only; database transactions are time-limited |
| Hash-chain tests | Every committed transaction sealed once into one chain; a balanced change invisible to reconciliation (transaction, entries and balances all altered consistently) caught by the chain; changed details and deleted transactions caught; re-sealing a tampered seal breaks the next link, and a full rewrite is caught against an externally recorded head; concurrent sealers never fork the chain; initiators recorded and visible to administrators |
| Chart of accounts tests | Every customer account gets its own liability account; each posting type produces the right debits and credits; the trial balance balances and satisfies the accounting equation, now and as of an earlier moment; fees need funds and are idempotent; the ledger API is admin-only; the database rejects an account whose normal side contradicts its type; reconciliation notices an entry with no chart account |
| Ledger tests | Each operation posts the right balanced entries; unbalanced postings, transactions without entries, and edits or deletes of history are rejected by PostgreSQL; reconciliation detects a balance changed outside the ledger |
| Model-based randomized test | 4,800 random operations per run (transfers, linked transfers, fees, reversals, retries, key conflicts, holds, posts, voids, expiry) match an independent model after every step; failures shrink to a minimal reproduction with a seed |
| Network fault injection (Toxiproxy) | Connections cut 8 times under load; clients retry with the same key; every acknowledged transfer exists exactly once, lost acknowledgements are replayed, books reconcile |
| Database crash injection | PostgreSQL killed with `SIGKILL` twice under load and recovered from its WAL; every acknowledged transfer survives, nothing posts twice, books reconcile |
| Migration test | Upgrading a database with pre-ledger history backfills entries and opening balances that reconcile exactly |
| Integration tests | Rollback on insufficient funds and on database constraint failures, key-reuse conflicts, cross-currency rejection. After every test the ledger must still reconcile |
| Unit tests (JUnit 5, Mockito, MockMvc) | Service rules, write order (ledger rows before balance updates), stale-read rejection, replay of simultaneous duplicates, ownership checks |
| Hot-account benchmark (opt-in) | Throughput and p50/p95/p99 latency as 0–100% of transfers pay one account; ledger reconciled after every run |

Fault testing method, results and limits: [docs/fault-testing.md](docs/fault-testing.md).

See [`ConcurrencyIntegrationTest`](backend/src/test/java/com/smartbank/transaction/ConcurrencyIntegrationTest.java), [`LedgerIntegrationTest`](backend/src/test/java/com/smartbank/ledger/LedgerIntegrationTest.java) and [`LedgerMigrationTest`](backend/src/test/java/com/smartbank/ledger/LedgerMigrationTest.java).

## How the ledger works

Every transaction posts journal lines whose debits equal its credits. Lines post to a **chart of accounts**:

| Code | Account | Type | Normal balance |
| --- | --- | --- | --- |
| 1000 | Cash and settlement | Asset | Debit |
| 2000 | Customer deposits (control account) | Liability | Credit |
| 2000-*number* | One per customer account, under 2000 | Liability | Credit |
| 3500 | FX position | Equity | Credit |
| 3900 | Opening balance equity | Equity | Credit |
| 4000 | Fee revenue | Revenue | Credit |

A customer's money is a liability of the bank (the bank owes it to them), so a credit increases it and a debit reduces it. Each currency has its own set of system accounts, and every new customer account gets its own ledger account automatically.

| Transaction | Journal lines |
| --- | --- |
| Deposit 100 | DR 1000 Cash 100 · CR customer 100 |
| Withdrawal 100 | DR customer 100 · CR 1000 Cash 100 |
| Transfer 100, A→B | DR A 100 · CR B 100 |
| Fee 2.50 | DR customer 2.50 · CR 4000 Fee revenue 2.50 |
| Hold posted for 60 of 100 | Same as a transfer of 60; the hold itself posts nothing |
| Reversal of 30 of a transfer A→B | DR B 30 · CR A 30 |
| Reversal of a deposit of 100 | DR customer 100 · CR 1000 Cash 100 |
| Opening balance 100 | DR 3900 Opening balance equity 100 · CR customer 100 |
| FX transfer: 100 CAD buys 73.33 USD | CAD: DR payer 100 · CR 3500 FX position 100 — USD: DR 3500 FX position 73.33 · CR payee 73.33 |

Entries are stored with signed amounts (positive = credit, negative = debit); the `journal_lines` view shows each entry as a debit or credit line on its chart account. Administrators get a **trial balance** per currency, optionally as of any past moment, which checks that total debits equal total credits and that **Assets = Liabilities + Equity + Revenue − Expenses**, with the customer control account rolling up every customer's balance.

`accounts.balance` is kept as a cached balance, because transfers lock the account row and check funds against it. The ledger is the record it must agree with, which [`LedgerReconciliation`](backend/src/main/java/com/smartbank/ledger/LedgerReconciliation.java) verifies. Balances that existed before the ledger (migration `V4`) were posted as opening balances dated before each account's first transaction.

The external and equity sides are ledger accounts without a row in `accounts`, so deposits and withdrawals never lock a shared row and do not serialize on it.

## How FX works

```
POST /fx/quotes    { fromAccountId: <CAD account>, toAccountId: <USD account>, amount: "100.00" }
  → { id, sellAmount: 100.00 CAD, buyAmount: 73.33 USD, rate: 0.7333, expiresAt: +60 s }
POST /fx/transfers { quoteId }   (with an Idempotency-Key)
  → FX_TRANSFER: amount 100.00 CAD, counterAmount 73.33 USD, fxRate 0.7333
```

- **Rates** are set per direction by administrators (1 base buys `rate` quote), because a bank's buy and sell rates differ; rates are append-only and the latest is current.
- **Quotes** lock the rate and both amounts. The received amount is rounded down to the target currency's minor unit (JPY has none), and a quote can be executed once, by the user who got it, before it expires; the quote row is locked while it is used, so racing executions post once. A failed execution (for example insufficient funds) rolls back and leaves the quote usable.
- **Ledger:** one transaction with four entries, balancing within each currency through that currency's **3500 FX position** account. The database's balance check now works per currency and allows two currencies only for FX transfers. The FX positions hold the bank's net position per currency; valuing them in one reporting currency (FX gain or loss) is not implemented.
- FX transfers are not batched and cannot be reversed yet.

## How tamper evidence works

```
seal 1: SHA-256(genesis, 1, transactions sealed in 1)        ─┐
seal 2: SHA-256(hash of seal 1, 2, transactions sealed in 2)  ├─ each seal covers its transactions, their
seal 3: SHA-256(hash of seal 2, 3, transactions sealed in 3) ─┘  ledger entries, and the previous seal
```

- Every few seconds the sealer ([`LedgerSealer`](backend/src/main/java/com/smartbank/ledger/LedgerSealer.java)) takes the committed transactions not yet sealed and appends one seal over them. Each transaction is hashed with all its fields (type, accounts, amount, request key, time, initiator, links) and all its ledger entries, in a fixed text form.
- Change, add or remove anything already sealed and the recomputed hash of that seal no longer matches; recompute that seal too and the next seal's link breaks. Rewriting every later seal is caught by comparing the head with a copy kept outside the database: the sealer logs every new head, and `GET /ledger/seals/head` returns it.
- The tests show the point: a 30.00 transfer quietly turned into 40.00, with its entries and both balances adjusted to match, passes every other reconciliation check, and only the chain catches it.
- Sealing runs after commit and in the background, so posting never waits on a single chain head; a PostgreSQL advisory lock keeps concurrent sealers from forking the chain. Transactions committed since the last seal (a few seconds' worth) are not yet covered.
- Every transaction records `initiated_by`, the user whose request created it (null for the system, such as the hold expiry job). Batched postings capture it on the request's thread, since batch workers have no login of their own.

## How balance checkpoints work

- Once a day (UTC), every ledger account that had activity gets a checkpoint: its cumulative debits and credits for all entries dated before midnight. A balance at any moment, for a statement or a trial balance, is the latest checkpoint before that moment plus the entries since.
- **Timing is the hard part.** An entry's time is set before its transaction commits, so a checkpoint taken too early could miss a transaction that commits afterwards, and checkpoints are append-only. So a boundary is checkpointed only once it is at least 10 minutes old, and every database transaction is limited to 2 minutes (`transaction_timeout`, which needs PostgreSQL 17 or later). Reconciliation also re-checks every checkpoint as the previous one plus the entries between them, so a checkpoint that ever missed an entry would be reported.
- Statements read from the customer's side: money in is positive, money out negative. They add up by construction and are checked: the opening balance plus every line equals the closing balance.
- Settings: `smartbank.ledger.checkpoint-period` (`P1D`), `checkpoint-horizon` (`PT10M`), `checkpoint-interval` (`PT15M`).

## How holds work

```
PENDING ──post (full or partial)──▶ POSTED    money moves through the ledger; remainder released
   │
   ├──void──────────────────────▶ VOIDED    reservation released
   └──expiry (default 7 days)───▶ EXPIRED   released by a scheduled job, or by a late post attempt
```

- Creating a hold raises the payer's `pendingDebits` and the payee's `pendingCredits`. **Available balance** = `balance - pendingDebits`, and every withdrawal, transfer and new hold is checked against it.
- A hold changes state exactly once. A database trigger rejects any other update and any delete.
- Retrying is safe: creating a hold is idempotent by `requestKey`; repeating a post with the same amount, or a void, returns the hold unchanged; anything conflicting returns 409.
- Account locks are taken last: creating a hold inserts it and its event first and reserves the money in one final conditional statement per account; posting, voiding and expiring lock only the hold row, write everything, then change both accounts in one batch. With every path locking accounts last and in the same order, holds, transfers and the expiry job cannot deadlock with each other.
- Reconciliation also checks that each account's pending amounts equal its open holds and that every posted hold matches its transfer.

## How linked transfers work

```
POST /api/v1/transfers/linked
{ "transfers": [
    { "fromAccountId": "<customer>", "toAccountId": "<merchant>", "amount": "47.50" },
    { "fromAccountId": "<customer>", "toAccountId": "<fees>",     "amount": "2.50"  } ] }
```

- **All or nothing.** Every leg is written in one SQL statement (transactions, ledger entries, events, webhook deliveries) and every balance change in one batch, inside one database transaction. If any leg is invalid or any account lacks funds, nothing posts, and the 409 names the leg or account.
- **Net funds check.** Each account must cover its net change across the group, so an account can pass on money it receives in the same group (A→B 60, B→C 60 works even if B starts empty). Checking legs one at a time would need a per-leg order that conflicts with taking locks in UUID order.
- **Each leg is an ordinary transfer** in the ledger, history and webhooks, tagged with `linkId` and `linkIndex`. The request key is stored on leg 0 only, so the existing unique constraint makes the group exactly-once; a single transfer can never replay a leg, or the other way round.

## How idempotency keys work

```
POST + Idempotency-Key ─▶ claim (user, key) ─┬─ completed ──────────▶ replay stored status + body (Idempotent-Replayed: true)
                                             ├─ different request ──▶ 422
                                             └─ new or unfinished ──▶ run with the key's operation key ─▶ store response ─▶ return it
```

- Each (user, key) maps to a server-generated **operation key**, which the ledger stores under a `UNIQUE` constraint. The response is stored in a separate step; if the server stops in between, the retry runs the same operation key, gets the original transaction back, and stores that.
- Stored: successes and business rejections (409 such as insufficient funds, 404). Not stored, so a retry runs again: validation errors (400), authorization failures (403, checked before the key is claimed), and unexpected errors.
- Requests are compared by a SHA-256 fingerprint of the operation and its parameters, with amounts normalized (`25` and `25.00` are the same request).
- Keys expire after 24 hours (`smartbank.idempotency.retention`) and an hourly job prunes them. After expiry the key can be used for a new request.
- Posting and voiding a hold are idempotent through the hold's own state, so they take no key.

## Tech stack

Java 21, Spring Boot 3.5, Spring Data JPA, Spring Security (OAuth2 resource server, JWT), PostgreSQL 17, Flyway, Testcontainers, Toxiproxy, JUnit 5, Mockito, React 19, TypeScript, Vite, Docker Compose, GitHub Actions.

## Run it locally

Requirements: Docker. The backend needs PostgreSQL 17 or later (Docker Compose provides it). Node.js 20.19+ for the frontend. JDK 21 and Maven are optional; tests can run in Docker.

Start PostgreSQL and the API (API on `http://localhost:8081`):

```bash
docker compose -f infra/compose.yml up --build -d
```

Start the frontend (`http://localhost:5173`, proxies `/api` to the backend):

```bash
cd frontend && npm install && npm run dev
```

Log in as `demo.a@smartbank.local` or `demo.b@smartbank.local` with the local demo password set in `infra/compose.yml`. Demo user A owns `DEMO-10001` (CAD) and `DEMO-10003` (USD, for trying FX); demo user B owns `DEMO-10002` (CAD). Demonstration CAD/USD rates are seeded.

Stop without deleting data with `docker compose -f infra/compose.yml down`. Adding `-v` deletes the database volume.

## Run the tests

With JDK 21 and Maven installed:

```bash
cd backend && mvn test
```

Or entirely in Docker (Testcontainers starts its own PostgreSQL through the Docker socket):

```bash
cd backend && docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -v "$PWD":/app -w /app maven:3.9-eclipse-temurin-21 mvn -B test
```

CI runs `mvn verify` on every push and pull request. The hot-account benchmark and the round-trip profile are opt-in (`-Dtest=RoundTripProfile -Dbenchmark=true` for the latter). The hot-account benchmark: `mvn test -Dtest=HotAccountBenchmark -Dbenchmark=true` (keep the Mac awake, for example with `caffeinate -i`).

## API

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
| `GET` | `/api/v1/ledger/seals/verify` | Recomputes the hash chain and reports the first broken seal. Admin only |
| `POST` | `/api/v1/transactions/{id}/reversals` | Optional `{ "amount" }`; without it, whatever is not yet reversed. Transfers: payee or admin. Deposits and withdrawals: admin |
| `POST` | `/api/v1/transfers/linked` | `{ "transfers": [ { "fromAccountId", "toAccountId", "amount" }, … ] }`, 1–20 legs, all or nothing. Caller must own every source |
| `GET` | `/api/v1/accounts/{id}/transactions` | Newest 100, owner only |
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

## Project layout

- `backend/src/main/java/com/smartbank/account`: accounts API, entity and locking repository
- `backend/src/main/java/com/smartbank/transaction`: deposits, withdrawals, transfers, holds, idempotency
- `backend/src/main/java/com/smartbank/ledger`: ledger entries, chart of accounts, trial balance, reconciliation
- `backend/src/main/java/com/smartbank/events`: outbox, webhook endpoints, signed delivery worker
- `backend/src/main/java/com/smartbank/idempotency`: per-user idempotency keys, stored responses, pruning
- `backend/src/main/java/com/smartbank/ratelimit`: token-bucket limiter, limits, filter
- `backend/src/main/java/com/smartbank/auth`: JWT login, registration, ownership checks
- `backend/src/main/resources/db/migration`: Flyway schema (V1 accounts, V2 transactions, V3 users and ownership, V4 ledger, V5 holds, V6 idempotency keys, V7 outbox and webhooks, V8 linked transfers, V9 reversals, V10 rate limits, V11 webhook hardening, V12 chart of accounts, V13 seals and initiator, V14 balance checkpoints, V15 multi-currency and FX)
- `backend/src/test`: unit, integration, concurrency, model-based and fault-injection tests, and the hot-account benchmark
- `frontend`: React + TypeScript UI
- `infra/compose.yml`: local PostgreSQL and backend

## Limitations and roadmap

Not implemented: refresh tokens or token revocation, MFA, account opening, pagination beyond the newest 100 transactions, FX revaluation and FX reversals. The demo password, JWT secret and webhook encryption key in `infra/compose.yml` are for local use only, and Compose allows http and private webhook targets so a receiver on your machine works; production must set `WEBHOOK_ENCRYPTION_KEY` and leave `WEBHOOK_ALLOW_HTTP` and `WEBHOOK_ALLOW_PRIVATE_TARGETS` off.

Next steps:
- Re-run the throughput benchmarks on a quiet machine.
- Close the remaining DNS-rebinding window for webhooks with an egress proxy, and allow rotating the secret-encryption key.
- Frontend navigation on phones (the sidebar is hidden below 520 px, leaving only the Overview page).

Development history by sprint: [Sprint 2](SPRINT2_README.md) (transactions), [Sprint 3](SPRINT3_README.md) (authentication), [frontend](frontend/README.md).
