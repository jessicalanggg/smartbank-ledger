# SmartBank Sprint 2 — Banking Transactions

## Apply to your existing project

You are on `feature/banking-transactions`. This ZIP contains a **complete project snapshot** (Sprint 1 + Sprint 2), not just a patch. Back up your current project, then copy the **contents** of this ZIP over the existing SmartBank project root, preserving `.git` (the ZIP has no `.git`). The existing `infra/compose.yml` maps backend host port **8081** to container port 8080.

From project root (PowerShell):

```powershell
# Run the Maven tests in the build stage; Dockerfile currently skips tests when packaging.
docker compose -f infra/compose.yml build backend
docker compose -f infra/compose.yml up -d --force-recreate backend
docker compose -f infra/compose.yml logs --tail=80 backend
docker compose -f infra/compose.yml exec db psql -U smartbank -d smartbank -c "SELECT version, description, success FROM flyway_schema_history;"
```

Expected Flyway migration version: `2`. **Do not** run `docker compose down -v`; that deletes database volumes.

## API examples (PowerShell)

```powershell
$base = 'http://localhost:8081/api/v1'
$from = '11111111-1111-4111-8111-111111111111'
$to = '22222222-2222-4222-8222-222222222222'
Invoke-RestMethod "$base/accounts"
Invoke-RestMethod "$base/accounts/$from/deposits" -Method Post -ContentType 'application/json' -Body (@{ amount='100.00'; requestKey=[guid]::NewGuid().ToString() } | ConvertTo-Json)
Invoke-RestMethod "$base/accounts/$from/withdrawals" -Method Post -ContentType 'application/json' -Body (@{ amount='25.00'; requestKey=[guid]::NewGuid().ToString() } | ConvertTo-Json)
$body = @{ fromAccountId=$from; toAccountId=$to; amount='200.00'; requestKey=([guid]::NewGuid().ToString()) } | ConvertTo-Json
Invoke-RestMethod "$base/transfers" -Method Post -ContentType 'application/json' -Body $body
Invoke-RestMethod "$base/accounts/$from/transactions"
Invoke-RestMethod "$base/accounts"
```

## Architecture and caveats

- All writes use `@Transactional`. The two account updates and transaction record are committed together, or rolled back together.
- Accounts are locked with `PESSIMISTIC_WRITE`; transfers lock both accounts in deterministic UUID order to avoid deadlocks.
- Transfers, deposits and withdrawals all require a client-provided `requestKey`. Retrying with the same key, sequentially or concurrently, returns the original transaction without posting again: the key is re-checked after the account lock is taken, and a `UNIQUE` constraint on `request_key` backs this up. Reusing a key for a different operation (other amount, account or type) returns 409. A request that fails, for example for insufficient funds, rolls back and does not consume its key.
- Clients should generate one key per user action and reuse it on retry. The React frontend does this for transfers, deposits and withdrawals.
- Transaction history is limited to the newest 100 records per account. Pagination and immutable ledger/accounting design are future work.
- This is a **local educational banking simulation** without authentication, authorization, production-grade audit controls, or fraud protections. Do not expose it publicly or use real customer data.
- Tests include Mockito unit tests, Testcontainers integration tests against real PostgreSQL, and concurrency tests (`ConcurrencyIntegrationTest`): 5,000 random transfers on 32 threads with money-conservation and ledger-reconciliation checks, plus simultaneous duplicate transfers, deposits and withdrawals. The provided Docker build runs `mvn ... -DskipTests package`; execute tests separately in Maven or CI.

## Git

```powershell
git status
git add .
git commit -m "Sprint 2: atomic banking transactions and history"
```
