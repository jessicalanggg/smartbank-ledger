# SmartBank

A banking transfer API built with Java 21, Spring Boot and PostgreSQL. It focuses on making money movement correct under retries and concurrency, and comes with a React frontend.

**Educational simulation only.** All data is synthetic. Do not expose it to the internet or use real personal or financial data.

## What it does

- **Accounts, deposits, withdrawals and transfers** through a versioned REST API (`/api/v1`), with transaction history per account.
- **Atomic money movement.** Each operation runs in one `@Transactional` unit: balance updates and the transaction record commit together or roll back together.
- **Idempotent requests.** Every deposit, withdrawal and transfer carries a client-generated `requestKey`. A retry with the same key returns the original transaction instead of posting again, even when many retries arrive at the same moment. Reusing a key for a different operation returns HTTP 409.
- **Deadlock-free locking.** Accounts are locked with `PESSIMISTIC_WRITE`. A transfer always locks its two accounts in UUID order, so opposing transfers (A→B and B→A) cannot deadlock.
- **Authentication and authorization.** Spring Security with stateless JWT (HS256, 30-minute expiry) and BCrypt password hashing. Users can only read, debit or view the history of accounts they own.
- **Database-enforced invariants.** Flyway migrations define `CHECK (balance >= 0)`, positive amounts, valid transaction shapes, and a `UNIQUE` request key, so the database rejects bad states even if application code is wrong.
- **React frontend** with login, accounts, history, deposits, withdrawals and transfers by account number, including safe retry with the same request key.

## Verified by tests

All tests run against real PostgreSQL in Testcontainers, apart from the unit tests.

| Test | What it proves |
| --- | --- |
| 5,000 random concurrent transfers (32 threads, 10 accounts) | Total money is conserved, no balance goes negative, no deadlocks, exactly one row per successful transfer, and every balance reconciles with the transaction log |
| 100 identical transfers fired at once | Posted exactly once; every caller gets the same transaction |
| 90 conflicting transfers sharing one key, fired at once | Posted at most once, although the requests lock different accounts |
| 100 identical deposits + 100 identical withdrawals fired at once | Each posted exactly once |
| Integration tests | Rollback on insufficient funds and on database constraint failures, key-reuse conflicts, cross-currency rejection |
| Unit tests (JUnit 5, Mockito, MockMvc) | Service rules, replay handling, ownership checks |

See [`ConcurrencyIntegrationTest`](backend/src/test/java/com/smartbank/transaction/ConcurrencyIntegrationTest.java) and [`BankingIntegrationTest`](backend/src/test/java/com/smartbank/transaction/BankingIntegrationTest.java).

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

CI runs `mvn verify` on every push and pull request.

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

Errors use RFC 9457 problem details: 400 invalid input, 401 bad credentials, 403 not the owner, 404 unknown account, 409 insufficient funds, conflicting key reuse or another business rule.

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
- `backend/src/main/java/com/smartbank/transaction`: deposits, withdrawals, transfers, idempotency
- `backend/src/main/java/com/smartbank/auth`: JWT login, registration, ownership checks
- `backend/src/main/resources/db/migration`: Flyway schema (V1 accounts, V2 transactions, V3 users and ownership)
- `backend/src/test`: unit, integration and concurrency tests
- `frontend`: React + TypeScript UI
- `infra/compose.yml`: local PostgreSQL and backend

## Limitations and roadmap

Not implemented: refresh tokens or token revocation, rate limiting, MFA, account opening, audit logging, pagination beyond the newest 100 transactions, and cross-currency transfers. The demo password and JWT secret in `infra/compose.yml` are for local use only.

Next steps:
- Double-entry ledger: append-only debit/credit entries with balances derived from them.
- Crash-recovery tests that kill the app or database mid-transfer and verify the books still balance.
- Hot-account throughput benchmarks comparing pessimistic and optimistic locking.

Development history by sprint: [Sprint 2](SPRINT2_README.md) (transactions), [Sprint 3](SPRINT3_README.md) (authentication), [frontend](frontend/README.md).
