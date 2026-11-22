# Testing

What the test suite proves, and how to run it. All tests run against real PostgreSQL in Testcontainers, apart from the unit tests.

## What the tests prove

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
| Agent mandate tests | Payments within the limits post at once and the ledger names the mandate; over a limit, money is held until the owner approves (paid) or declines (released), and the payee cannot capture it; rejected when the mandate does not ask; payees outside the allowed list rejected and every attempt recorded; at most 5 payments await approval; 50 simultaneous payments against a 100.00 daily limit post exactly 33 × 3.00; revoking stops the key and a payment already past the key check, and declines what awaits approval; agent keys only work on the agent API; idempotent retries; the database refuses usage over the limit and any change to a mandate except revocation; reconciliation checks usage against payments after every test |
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

Fault testing method, results and limits: [docs/fault-testing.md](fault-testing.md).

See [`ConcurrencyIntegrationTest`](../backend/src/test/java/com/smartbank/transaction/ConcurrencyIntegrationTest.java), [`LedgerIntegrationTest`](../backend/src/test/java/com/smartbank/ledger/LedgerIntegrationTest.java) and [`LedgerMigrationTest`](../backend/src/test/java/com/smartbank/ledger/LedgerMigrationTest.java).

## Run the tests

With JDK 21 and Maven installed:

```bash
cd backend && mvn test
```

Or entirely in Docker (Testcontainers starts its own PostgreSQL through the Docker socket):

```bash
cd backend && docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -v "$PWD":/app -w /app maven:3.9-eclipse-temurin-21 mvn -B test
```

CI runs `mvn verify`, the frontend type-check and build, and the agent demo type-check on every push and pull request. The hot-account benchmark and the round-trip profile are opt-in (`-Dtest=RoundTripProfile -Dbenchmark=true` for the latter). The hot-account benchmark: `mvn test -Dtest=HotAccountBenchmark -Dbenchmark=true` (keep the Mac awake, for example with `caffeinate -i`).
