# Hot-account benchmark

A busy merchant account receives a large share of all transfers. Every transfer into it needs that account's row lock, so its throughput is bounded by how long the lock is held. This benchmark measures that bound for SmartBank and explains it.

Harness: [`HotAccountBenchmark`](../backend/src/test/java/com/smartbank/benchmark/HotAccountBenchmark.java). It is excluded from the normal test run:

```bash
cd backend && mvn test -Dtest=HotAccountBenchmark -Dbenchmark=true
```

Options: `-Dbenchmark.threads=32 -Dbenchmark.transfers=4000 -Dbenchmark.runs=3`, and `-Dbenchmark.syncCommit=off` for diagnosis only.

**Setup.** 100 customer accounts pay one merchant account. For each row, the given share of transfers goes to the merchant; the rest go between random customers. Each run warms up first, then measures; every run ends with a full ledger reconciliation, which must be clean. Calls go straight to `BankingService`, so HTTP and JWT handling are not included.

**Environment.** Apple M5 (10 cores, 16 GB), macOS 26.6, Docker Desktop 29.8, PostgreSQL 17 in Testcontainers, JDK 21 in a Maven container, HikariCP pool of 10. Absolute numbers on Docker Desktop vary by up to about 30% between runs; the shape of the results is stable.

## Baseline: pessimistic locking (current design)

3,000 transfers per run, 32 client threads, 3 runs per row, median run shown.

| Transfers into hot account | Transfers/s | p50 ms | p95 ms | p99 ms |
| --- | --- | --- | --- | --- |
| 0% | 614 | 16.2 | 216.8 | 475.0 |
| 25% | 520 | 11.9 | 328.8 | 809.5 |
| 50% | 383 | 16.9 | 438.0 | 1398.5 |
| 75% | 242 | 45.8 | 706.5 | 1922.5 |
| 100% | 199 | 48.3 | 772.0 | 2548.0 |

When every transfer pays the merchant, throughput falls to about a third and p99 latency rises more than fivefold.

With 10 client threads (equal to the pool size), throughput is similar (378 → 230 transfers/s from 0% to 100%) but p99 stays under 100 ms: most of the multi-second tail above is threads queueing for a database connection behind the locked row.

## Where the time goes

| Measurement | Value |
| --- | --- |
| One database round trip (`SELECT 1`) | 0.32–0.38 ms |
| One-row insert and commit | 0.46–0.50 ms |
| One transfer, nothing else running | 4.85 ms |
| Hot-account ceiling, 100% share | ≈ 200 transfers/s ≈ 1 / 4.85 ms |

- **The hot account's lock is held for almost the whole transfer.** A transfer locks both accounts near the start, then re-checks the request key, updates both balances, inserts the transaction and two ledger entries, and commits: about a dozen round trips, all while holding the locks. With every transfer needing the merchant's lock, transfers run one at a time, so the ceiling is one transfer per service time.
- **Disk sync is not the bottleneck.** With `synchronous_commit=off` the hot-account ceiling moved only from 202 to 244 transfers/s.
- **CPU is not the bottleneck.** During runs PostgreSQL used under one core and the client JVM under half a core of ten. Sampling `pg_stat_activity` showed active sessions almost all waiting on row locks (`Lock:tuple`, `Lock:transactionid`).
- **The same effect caps the 0% case.** Ten connections at 4.85 ms each could reach about 2,000 transfers/s, but measured 0% throughput is 380–610. Each transfer holds two of 100 customer locks for its whole duration, so concurrent transfers keep queueing behind each other even without a hot account.

## Fix: shorten the critical section

Deposits, withdrawals and transfers now hold account locks only for the last step of the transaction ([`BankingService`](../backend/src/main/java/com/smartbank/transaction/BankingService.java)):

1. Read the accounts without locking them: existence, currency, and a fast insufficient-funds check that may be stale and is not authoritative.
2. Insert the transaction row and its ledger entries. This takes only `KEY SHARE` locks on the referenced accounts, which do not block other transactions' balance updates.
3. Update the balances last, as single atomic statements in UUID order: a conditional debit (`WHERE balance - pending_debits >= amount`, otherwise the whole transaction rolls back as insufficient funds) and a credit. Row locks are taken here and released at commit.

Idempotency no longer needs a re-check under the account locks. A simultaneous duplicate blocks on the `UNIQUE` `request_key` index until the first request commits, then fails, and is replayed from the committed row.

The hold path still locks accounts up front, since it must read and change pending amounts together. Its account lock changed from `FOR UPDATE` to `FOR NO KEY UPDATE`: `FOR UPDATE` conflicts with the `KEY SHARE` locks that step 2 takes, so a hold and a transfer could otherwise deadlock.

All 59 tests pass, including the concurrency suites (5,000 random transfers; 4,000 mixed holds, posts, voids, expiries and transfers; simultaneous duplicate requests), repeated three times with no deadlocks.

## Results

Same settings as the baseline: 3,000 transfers per run, 32 client threads, pool of 10, median of 3 runs. Each version was measured in two separate sessions; both are shown.

| Transfers into hot account | Before: transfers/s | After: transfers/s | Before: p99 ms | After: p99 ms |
| --- | --- | --- | --- | --- |
| 0% | 614 / 564 | 648 / 575 | 475 / 480 | 187 / 176 |
| 25% | 520 / 466 | 698 / 678 | 810 / 936 | 171 / 181 |
| 50% | 383 / 373 | 618 / 653 | 1,399 / 1,565 | 180 / 185 |
| 75% | 242 / 286 | 579 / 604 | 1,923 / 2,095 | 205 / 206 |
| 100% | 199 / 209 | 554 / 521 | 2,548 / 3,390 | 268 / 296 |

- **With every transfer paying one account, throughput rose about 2.6× (≈200 → ≈540 transfers/s) and p99 latency fell about 10× (≈3 s → under 300 ms).**
- Throughput is now nearly flat as the hot share grows: a busy account costs about 15% instead of 65%.
- Throughput with no hot account is unchanged (≈600 transfers/s), but its p99 fell from ≈480 ms to under 200 ms because transfers no longer queue behind each other's whole-transaction locks. Median latency at low contention rose (≈15 → ≈40 ms) as requests now share the connection pool more evenly instead of some finishing quickly while others wait seconds.
- The remaining ceiling of ≈600 transfers/s is not hot-account locking. It is consistent across hot shares, and it was not investigated further; candidates are the round-trip count per transfer (≈5 ms of service time) and the Docker Desktop network path.

## After the transactional outbox

The outbox adds two SQL statements to every posting (the event and its delivery rows), both before the account-lock window. An interleaved A/B run (before, after, before, after) was taken while the machine was under heavy unrelated load (load average ≈ 15 on 10 cores); the noise exceeded any difference, with the pre-outbox code slower in both pairs. The effect is not yet quantified and should be re-measured on a quiet machine.

## Round trips per operation

The service time of a transfer was mostly network round trips to PostgreSQL. [`RoundTripProfile`](../backend/src/test/java/com/smartbank/benchmark/RoundTripProfile.java) counts them exactly: all traffic goes through Toxiproxy, each operation is timed with and without a 20 ms delay on every database response, and

> round trips = (median time with delay − median time without) / 20 ms

CPU time and machine load cancel out of the difference, so the counts are reliable even on a busy machine. It also prints the statements one transfer sends, from PostgreSQL's statement log.

```bash
cd backend && mvn test -Dtest=RoundTripProfile -Dbenchmark=true
```

| Operation | Before | After |
| --- | --- | --- |
| Transfer (service) | 17.4 | 6.3 |
| Deposit (service) | 14.9 | 6.2 |
| Withdrawal (service) | 14.5 | 6.0 |
| Create hold (service) | 13.6 | 9.9 |
| Post hold (service) | 20.8 | 9.9 |
| Transfer over HTTP, with idempotency key | 24.5 | 11.2 |
| Retry of a committed transfer | 1.2 | 1.2 |

**What the statement log showed** for one transfer, before:

1. Idempotency lookup; 2–3. each account read separately; 4–5. a `SELECT` before inserting the transaction; 6. insert; 7–8. a `SELECT` before each ledger entry; 9–10. two entry inserts; 11–12. outbox event and deliveries; 13–14. debit and credit; 15. commit.

Three of those `SELECT`s were hidden Hibernate work: the entities have ids assigned in Java, so Spring Data's `save()` cannot tell they are new and calls `merge()`, which reads before inserting.

**Changes:**

- Both accounts are read in one query.
- The transaction, its two ledger entries, its outbox event and its webhook deliveries are written in **one SQL statement** (data-modifying CTEs; foreign keys are checked at the end of the statement and the ledger balance at commit) by [`PostingWriter`](../backend/src/main/java/com/smartbank/transaction/PostingWriter.java).
- The debit and credit go in **one JDBC batch**. The server still runs them in the given order, so the UUID lock order is unchanged. Both use one statement shape: a negative delta applies only if the available balance covers it.
- `Hold` implements `Persistable`, so saving a new hold is one `INSERT` instead of `SELECT` + `INSERT`; Hibernate batching groups the account updates in a hold's flush.
- Outbox events for holds are written in one statement instead of two.
- The idempotency claim is one statement in the common case (insert the key, or read the live one), with the earlier multi-step path kept for expired keys and a rare race.

A transfer is now: idempotency lookup, account read, the write, the balance batch, and the commit. The remaining measured ≈1 round trip above those five was not identified.

All 84 tests pass with the new write path, including the model-based test and the network-cut and `kill -9` crash tests.

**Throughput not re-measured.** The machine was under heavy unrelated load (load average ≈ 11 on 10 cores) when this was done, which makes throughput numbers unreliable. With the service time now dominated by about 6 instead of 17 round trips, the benchmark above should be re-run on a quiet machine.

## Possible next steps

- Lock a hold's two accounts with one `SELECT … ORDER BY id FOR NO KEY UPDATE` (needs the Java lock order changed to PostgreSQL's unsigned UUID order everywhere).
- The same short-critical-section design for the hold path.
- For accounts hotter than one row can serve, split the balance into several sub-balance rows, or apply credits asynchronously (safe for incoming money because the cached balance can only lag low).
