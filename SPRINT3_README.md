# SmartBank Sprint 3 — Authentication and Account Authorization

This package builds on the verified Sprint 2 project. It introduces stateless JWT authentication, BCrypt password hashing, registered users, and owner checks at the HTTP boundary.

## Local deployment

1. Back up your working Sprint 2 folder and database if needed. On your existing `feature/banking-transactions` branch, create `feature/security-authentication`.
2. Extract this archive, copy the **contents** of `smartbank-sprint3/` into the existing SmartBank project root. Keep the original `.git/` folder.
3. From the project root run:

```powershell
docker compose -f infra/compose.yml build backend
docker compose -f infra/compose.yml up -d --force-recreate backend
docker compose -f infra/compose.yml logs --tail=100 backend
```

4. Check migration V3:

```powershell
docker compose -f infra/compose.yml exec db psql -U smartbank -d smartbank -c "SELECT version, description, success FROM flyway_schema_history;"
```

5. Run automated tests (requires Docker Desktop):

```powershell
docker run --rm -v "${PWD}/backend:/app" -w /app maven:3.9-eclipse-temurin-21 mvn test
```

## Demo logins (LOCAL DEVELOPMENT ONLY)

The Compose file sets `DEMO_PASSWORD=LocalDemoPass123!` for both seeded users. **Never deploy with these demo credentials or the fallback JWT signing secret.** Demo user A owns DEMO-10001, demo user B owns DEMO-10002. Existing transactions and balances are retained.

```powershell
$base = 'http://localhost:8081/api/v1'
$login = @{ email='demo.a@smartbank.local'; password='LocalDemoPass123!' } | ConvertTo-Json
$auth = Invoke-RestMethod "$base/auth/login" -Method Post -ContentType 'application/json' -Body $login
$headers = @{ Authorization = "Bearer $($auth.accessToken)" }
Invoke-RestMethod "$base/accounts" -Headers $headers
```

Demo user A should see only DEMO-10001. A transfer from A to B is allowed, but a transfer from B to A using A's token is denied with HTTP 403.

```powershell
$body = @{ fromAccountId='11111111-1111-4111-8111-111111111111'; toAccountId='22222222-2222-4222-8222-222222222222'; amount='1.00'; requestKey=[guid]::NewGuid().ToString() } | ConvertTo-Json
Invoke-RestMethod "$base/transfers" -Headers $headers -Method Post -ContentType 'application/json' -Body $body
```

Registering a new user:

```powershell
$register = @{email='new.user@example.test'; password='UseAUniqueLongPassword!2026'} | ConvertTo-Json
Invoke-RestMethod "$base/auth/register" -Method Post -ContentType 'application/json' -Body $register
```

Newly registered users own no accounts; account opening is deferred to a future sprint.

## Architecture and limitations

- `V3__users_and_account_ownership.sql` creates `app_users` and nullable `accounts.owner_user_id` for a backwards-compatible migration. Unassigned accounts are not accessible via authenticated account endpoints.
- JWT tokens are signed with HS256, expire in 30 minutes, and carry the user's UUID as `sub`. Set a strong, private `JWT_SECRET` in production; the Compose default is **local-only**.
- BCrypt hashes passwords; only hashes are stored. This is a demo system, not production banking software.
- Controllers enforce ownership for account reads, deposits, withdrawals, transfer source accounts, and history. Transfer destinations may belong to another user.
- This release does not implement token revocation, refresh tokens, rate limiting, email verification, MFA, account-opening workflows, account lockout, audit logging, or production secrets management. No admin endpoints are exposed.
- The prior Sprint 2 unit tests are preserved. New ownership unit tests are included. **The build and runtime have not been executed in this environment**; validate using the commands above before considering Sprint 3 complete.
