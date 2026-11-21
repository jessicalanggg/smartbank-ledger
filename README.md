# SmartBank

A banking transfer API built with Java 21, Spring Boot and PostgreSQL. It focuses on making money movement correct under retries and concurrency, and comes with a React frontend.

**Educational simulation only.** All data is synthetic. Do not expose it to the internet or use real personal or financial data.

## What it does

- **Accounts, deposits, withdrawals and transfers** through a versioned REST API (`/api/v1`), with transaction history per account.
- **Double-entry ledger.** Every transaction posts balanced, append-only ledger entries: a transfer debits one customer account and credits another; a deposit or withdrawal posts against an `EXTERNAL_CLEARING` account. PostgreSQL rejects at commit any transaction whose entries do not sum to zero, and rejects any `UPDATE` or `DELETE` of entries or transactions. A reconciliation check proves every cached account balance equals the sum of its entries.
- **Two-phase transfers (holds).** A payer places a hold that reserves money without moving it, the way a card authorization does. The payee then posts it (in full or in part, releasing the rest) or voids it, and unposted holds expire automatically. Each account reports a posted balance, an available balance and pending amounts, and PostgreSQL rejects any change that would make the available balance negative.
- **Atomic money movement.** Each operation runs in one `@Transactional` unit: balance updates, the transaction record and its ledger entries commit together or roll back together.
- **Idempotent requests.** Every deposit, withdrawal and transfer carries a client-generated `requestKey`. A retry with the same key returns the original transaction instead of posting again, even when many retries arrive at the same moment. Reusing a key for a different operation returns HTTP 409.
- **Short, deadlock-free locking.** A transfer writes its transaction and ledger entries first, then updates both balances last with atomic statements in UUID order (the debit only succeeds if available funds cover it). Account locks are held only for those final statements and the commit, and opposing transfers (A→B and B→A) cannot deadlock.
- **Benchmarked hot-account throughput.** When every transfer pays one account, this design sustains about 2.6× the throughput of locking both accounts for the whole transfer, with about 10× lower p99 latency (≈540 vs ≈200 transfers/s, p99 under 300 ms vs ≈3 s). See [docs/benchmarks.md](docs/benchmarks.md).
- **Authentication and authorization.** Spring Security with stateless JWT (HS256, 30-minute expiry) and BCrypt password hashing. Users can only read, debit or view the history of accounts they own.
- **Database-enforced invariants.** Flyway migrations define `CHECK (balance >= 0)`, positive amounts, valid transaction shapes, a `UNIQUE` request key, balanced ledger postings and append-only history, so the database rejects bad states even if application code is wrong.
- **React frontend** with login, accounts, history, deposits, withdrawals and transfers by account number, including safe retry with the same request key.

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
| Hold tests | Reservation, full and partial posting, voids, expiry (including a post that arrives after expiry), idempotency, and database rules on hold state changes |
| Hold API tests (real JWT security) | Only the payer can place a hold, only the payee can post or void it, only the two parties can see it |
| Ledger tests | Each operation posts the right balanced entries; unbalanced postings, transactions without entries, and edits or deletes of history are rejected by PostgreSQL; reconciliation detects a balance changed outside the ledger |
| Migration test | Upgrading a database with pre-ledger history backfills entries and opening balances that reconcile exactly |
| Integration tests | Rollback on insufficient funds and on database constraint failures, key-reuse conflicts, cross-currency rejection. After every test the ledger must still reconcile |
| Unit tests (JUnit 5, Mockito, MockMvc) | Service rules, write order (ledger rows before balance updates), stale-read rejection, replay of simultaneous duplicates, ownership checks |
| Hot-account benchmark (opt-in) | Throughput and p50/p95/p99 latency as 0–100% of transfers pay one account; ledger reconciled after every run |

See [`ConcurrencyIntegrationTest`](backend/src/test/java/com/smartbank/transaction/ConcurrencyIntegrationTest.java), [`LedgerIntegrationTest`](backend/src/test/java/com/smartbank/ledger/LedgerIntegrationTest.java) and [`LedgerMigrationTest`](backend/src/test/java/com/smartbank/ledger/LedgerMigrationTest.java).

## How the ledger works

| Transaction | Entries (signed; positive increases the ledger account) |
| --- | --- |
| Deposit 100 | `CUSTOMER(account) +100`, `EXTERNAL_CLEARING -100` |
| Withdrawal 100 | `CUSTOMER(account) -100`, `EXTERNAL_CLEARING +100` |
| Transfer 100 | `CUSTOMER(from) -100`, `CUSTOMER(to) +100` |
| Opening balance 100 | `CUSTOMER(account) +100`, `OPENING_EQUITY -100` |
| Hold posted for 60 of 100 | Same as a transfer of 60; the hold itself posts no entries |

`accounts.balance` is kept as a cached balance, because transfers lock the account row and check funds against it. The ledger is the record it must agree with, which [`LedgerReconciliation`](backend/src/main/java/com/smartbank/ledger/LedgerReconciliation.java) verifies. Balances that existed before the ledger (migration `V4`) were posted as opening balances dated before each account's first transaction.

The external and equity sides are ledger accounts without a row in `accounts`, so deposits and withdrawals never lock a shared row and do not serialize on it.

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
- Every path locks the hold first, then both accounts in UUID order, so holds, transfers and the expiry job cannot deadlock with each other.
- Reconciliation also checks that each account's pending amounts equal its open holds and that every posted hold matches its transfer.

## Tech stack

Java 21, Spring Boot 3.5, Spring Data JPA, Spring Security (OAuth2 resource server, JWT), PostgreSQL 17, Flyway, Testcontainers, JUnit 5, Mockito, React 19, TypeScript, Vite, Docker Compose, GitHub Actions.

## Run it locally

Requirements: Docker. Node.js 20.19+ for the frontend. JDK 21 and Maven are optional; tests can run in Docker.

Start PostgreSQL and the API (API on `http://localhost:8081`):

```bash
docker compose -f infra/compose.yml up --build -d
```

Start the frontend (`http://localhost:5173`, proxies `/api` to the backend):

```bash
cd frontend && npm install && npm run dev
```

Log in as `demo.a@smartbank.local` or `demo.b@smartbank.local` with the local demo password set in `infra/compose.yml`. Demo user A owns `DEMO-10001`; demo user B owns `DEMO-10002`.

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

CI runs `mvn verify` on every push and pull request. The hot-account benchmark is opt-in: `mvn test -Dtest=HotAccountBenchmark -Dbenchmark=true` (keep the Mac awake, for example with `caffeinate -i`).

## API

All endpoints except health, login and register need `Authorization: Bearer <token>`.

| Method | Path | Notes |
| --- | --- | --- |
| `GET` | `/actuator/health` | Public |
| `POST` | `/api/v1/auth/register` | Public. New users own no accounts yet |
| `POST` | `/api/v1/auth/login` | Public. Returns `accessToken` |
| `GET` | `/api/v1/accounts` | Accounts owned by the caller |
| `GET` | `/api/v1/accounts/{id}` | Owner only |
| `POST` | `/api/v1/accounts/{id}/deposits` | `{ "amount": "100.00", "requestKey": "<uuid>" }` |
| `POST` | `/api/v1/accounts/{id}/withdrawals` | `{ "amount": "25.00", "requestKey": "<uuid>" }` |
| `POST` | `/api/v1/transfers` | `{ "fromAccountId", "toAccountId", "amount", "requestKey" }`. Caller must own the source |
| `POST` | `/api/v1/transfers/by-account-number` | `{ "fromAccountId", "toAccountNumber", "amount", "requestKey" }` |
| `GET` | `/api/v1/accounts/{id}/transactions` | Newest 100, owner only |
| `POST` | `/api/v1/holds` | `{ "fromAccountId", "toAccountId", "amount", "requestKey", "expiresInSeconds"? }`. Caller must own the source |
| `GET` | `/api/v1/holds/{id}` | Payer or payee only |
| `POST` | `/api/v1/holds/{id}/post` | Optional `{ "amount" }` for a partial post. Payee only |
| `POST` | `/api/v1/holds/{id}/void` | Payee only |
| `GET` | `/api/v1/accounts/{id}/holds` | Holds in or out of the account, newest 100, owner only |

Account responses include `balance` (posted), `availableBalance`, `pendingDebits` and `pendingCredits`.

Errors use RFC 9457 problem details: 400 invalid input, 401 bad credentials, 403 not the owner, 404 unknown account or hold, 409 insufficient funds, conflicting key reuse, a hold that is already final or expired, or another business rule.

Example:

```bash
TOKEN=$(curl -s localhost:8081/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"demo.a@smartbank.local","password":"<demo password>"}' | jq -r .accessToken)

curl -s localhost:8081/api/v1/transfers -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"fromAccountId\":\"11111111-1111-4111-8111-111111111111\",\"toAccountId\":\"22222222-2222-4222-8222-222222222222\",\"amount\":\"10.00\",\"requestKey\":\"$(uuidgen)\"}"
```

Sending the same command again with the same `requestKey` returns the same transaction and does not move money twice.

## Project layout

- `backend/src/main/java/com/smartbank/account`: accounts API, entity and locking repository
- `backend/src/main/java/com/smartbank/transaction`: deposits, withdrawals, transfers, holds, idempotency
- `backend/src/main/java/com/smartbank/ledger`: ledger entries, posting accounts, reconciliation
- `backend/src/main/java/com/smartbank/auth`: JWT login, registration, ownership checks
- `backend/src/main/resources/db/migration`: Flyway schema (V1 accounts, V2 transactions, V3 users and ownership, V4 ledger, V5 holds)
- `backend/src/test`: unit, integration and concurrency tests
- `frontend`: React + TypeScript UI
- `infra/compose.yml`: local PostgreSQL and backend

## Limitations and roadmap

Not implemented: refresh tokens or token revocation, rate limiting, MFA, account opening, audit logging, pagination beyond the newest 100 transactions, and cross-currency transfers. The demo password and JWT secret in `infra/compose.yml` are for local use only.

Next steps:
- Fewer round trips per transfer to raise the overall throughput ceiling, and the short-lock design for holds.
- Fault testing: property-based operation sequences (jqwik) and crash or network-failure injection mid-transfer (Toxiproxy).
- Idempotency keys scoped per user with expiry, replaying the original response.
- Transactional outbox with signed webhooks for posted, voided and expired events.
- Holds in the frontend (the API and tests are complete; the UI does not show holds yet).

Development history by sprint: [Sprint 2](SPRINT2_README.md) (transactions), [Sprint 3](SPRINT3_README.md) (authentication), [frontend](frontend/README.md).
