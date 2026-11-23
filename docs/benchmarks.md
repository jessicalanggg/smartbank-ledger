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

All 84 tests pass with the new write path, including the model-based test (now the seeded simulation) and the network-cut and `kill -9` crash tests.

**Throughput** with this write path was re-measured on a quiet machine: the "Off" columns of the batched-posting results below are this path without batching, at about 700 transfers/s with every transfer paying one account.

## Batched posting (group commit)

[`PostingBatcher`](../backend/src/main/java/com/smartbank/transaction/PostingBatcher.java) queues concurrent transfer, deposit and withdrawal requests. A worker takes everything queued (up to 200) and posts it in one database transaction:

1. Look up every request key in one query; committed ones are replayed.
2. Lock every account the batch touches with one `SELECT … WHERE id IN (…) ORDER BY id FOR NO KEY UPDATE`.
3. Decide each transfer in arrival order against the locked balances, exactly as if they ran one after another. A rejected transfer (insufficient funds, unknown account, different currencies) gets its own error; the rest of the batch still posts.
4. Write all accepted transfers, with ledger entries, events and webhook deliveries, in one statement, and each account's net balance change in one batch.
5. Commit, then answer every caller. Nobody hears "success" before the commit.

Batches form on their own: while a worker posts one batch, new requests queue for the next. Under light load a batch is one transfer; under load it grows (2,000 concurrent transfers into one account averaged about 12 per batch). Settings: `smartbank.batching.enabled` (true), `workers` (2), `max-size` (200), `linger` (0 ms).

**Lock order.** The batch locks accounts in PostgreSQL's `ORDER BY id` order, which is unsigned byte order. Java's `UUID.compareTo` compares signed numbers and disagrees for about half of all pairs, so every path now uses one shared comparator ([`UuidOrder`](../backend/src/main/java/com/smartbank/common/UuidOrder.java)) that matches PostgreSQL. Without that, a batch and a single transfer could lock the same two accounts in opposite orders.

**A deadlock the tests found.** A batch locks accounts and then inserts request keys; the unbatched path inserts its key and then locks accounts. If the same key is in flight on both at once, each can wait for the other, and PostgreSQL aborts one. A test that forces exactly this showed the whole batch failing, unrelated transfers included. Now a batch that loses a deadlock (or hits a duplicate key) settles each transfer on the unbatched path, where the contested one is replayed and the others post, and a single transfer chosen as the victim retries. Either way both callers get the same transaction and the key posts once.

**Results.** Re-measured on 2026-11-22 on a quiet machine (on mains power, other apps and containers stopped; load average about 3.4 before the first run and 5–7.5 during them, most of it the benchmark itself). Interleaved runs (off, on, off, on), 3,000 transfers per row, 32 client threads, pool of 10, three runs per row with the median shown. The two runs of each setting agree closely:

| Transfers into hot account | Off: transfers/s | On: transfers/s | Off: p99 ms | On: p99 ms |
| --- | --- | --- | --- | --- |
| 0% | 949 / 1,090 | 1,912 / 1,903 | 133 / 99 | 26 / 26 |
| 25% | 1,240 / 1,224 | 1,930 / 2,072 | 106 / 105 | 29 / 28 |
| 50% | 1,050 / 977 | 2,078 / 2,051 | 117 / 120 | 25 / 27 |
| 75% | 825 / 833 | 1,949 / 2,085 | 155 / 152 | 40 / 26 |
| 100% | 680 / 704 | 2,415 / 2,148 | 328 / 283 | 27 / 29 |

- With every transfer paying one account, batching gives about **3× the throughput** (≈700 → ≈2,100–2,400 transfers/s) and cuts p99 latency about **10×** (≈300 ms → under 30 ms).
- Throughput no longer drops as traffic concentrates on one account: without batching it falls from about 1,000 to 700 transfers/s as the hot share rises; with batching it stays at about 2,000 or more, and the hot case is among the fastest, because one row lock and one commit cover a whole batch of transfers into it.
- With no hot account, batching still roughly doubles throughput and cuts p99 about 4×, from sharing commits.
- An earlier measurement on a heavily loaded machine (load average 12–14) reported 5–6×. Its unbatched baseline was depressed by the load (305–419 transfers/s at 100%, against about 700 here), which overstated the gain; the figures above replace it.

**Verified** by the full suite running through the batcher (97 tests): the model-based test (now the seeded simulation), network-cut and `kill -9` crash tests, the 5,000-transfer and mixed hold/transfer concurrency tests, and batch tests for isolation of a rejected transfer, arrival-order decisions, duplicate keys inside a batch, replays, and the deadlock case above.

**Scope.** Transfers (including by account number), deposits, withdrawals and linked transfers are batched, together in the same batches. Reversals and holds still post one at a time.

### Deposits and withdrawals

Deposits and withdrawals join the same batches as transfers and are decided by the same rules as on their own: a deposit needs its account to exist, a withdrawal needs available funds, and both are decided in arrival order, so a withdrawal can use money deposited earlier in the same batch. Request keys are shared across the three types inside a batch, so a deposit key reused for a withdrawal is rejected there too.

`-Dbenchmark.mode=deposits`, interleaved (off, on, off, on), 3,000 deposits per row, 32 threads; machine load 4.5–10:

| Deposits into hot account | Off: deposits/s | On: deposits/s | Off: p99 ms | On: p99 ms |
| --- | --- | --- | --- | --- |
| 0% | 511 / 432 | 1,691 / 1,640 | 317 / 415 | 47 / 60 |
| 50% | 605 / 654 | 2,855 / 1,726 | 192 / 213 | 27 / 47 |
| 100% | 696 / 593 | 1,296 / 2,289 | 206 / 251 | 412 / 39 |

About 2–5× the throughput, and p99 4–9× lower in five of six pairs; the first all-hot batched run had one slow tail (412 ms).

### Linked transfers

A linked group joins a batch as one unit. It is accepted only if every leg's accounts exist and share a currency and every account covers its net change across the group, given the balances so far in the batch; then all its legs are written, otherwise none, and the rest of the batch is unaffected. The checks and error messages are the same as on the unbatched path. The group's request key is shared with the other postings in the batch, and a committed group is replayed.

`-Dbenchmark.mode=linked`: groups of two legs, one to the hot account (or a random one), one to another random customer. Interleaved, 3,000 groups per row, 32 threads; machine load 4.4–8.3:

| Groups into hot account | Off: groups/s | On: groups/s | Off: p99 ms | On: p99 ms |
| --- | --- | --- | --- | --- |
| 0% | 600 / 701 | 1,265 / 759 | 238 / 164 | 48 / 197 |
| 50% | 755 / 672 | 1,386 / 1,530 | 178 / 355 | 41 / 47 |
| 100% | 608 / 395 | 1,650 / 1,549 | 322 / 354 | 33 / 44 |

With the hot account involved, about 2–4× the throughput and p99 under 50 ms. With no hot account, one pair improved about 2× and the other barely (759 vs 701 groups/s, with a slower tail).

## Short locks for holds

Holds used to lock both accounts first and keep them locked while writing the hold, the transfer, the ledger entries and the event. They now follow the same rule as transfers ([`HoldService`](../backend/src/main/java/com/smartbank/transaction/HoldService.java)):

- **Create:** read the accounts without locking, insert the hold and its event, then reserve the money last with one conditional statement per account; the payer's only applies if its available balance covers the hold. A simultaneous identical request waits on the hold's unique request key and is replayed.
- **Post, void, expire:** lock the hold row (only requests for the same hold contend on it), write the transfer, the hold's new state and the event, then apply both accounts' changes (posted balance and pending amounts together) in one batch at the very end.

All account changes now go through one statement, `UPDATE accounts SET balance = balance + ?, pending_debits = pending_debits + ?, pending_credits = pending_credits + ? … WHERE id = ? AND available balance afterwards >= 0`, so the Java `Account` entity no longer changes balances at all.

**Results.** `-Dbenchmark.mode=holds`: each operation creates a hold and posts it. Interleaved runs (before, after, before, after), 2,000 operations per row, 32 threads; machine load rose from about 4 to 9 during the runs.

| Holds into hot account | Before: ops/s | After: ops/s | Before: p99 ms | After: p99 ms |
| --- | --- | --- | --- | --- |
| 0% | 242 / 215 | 344 / 335 | 794 / 700 | 272 / 314 |
| 50% | 238 / 240 | 299 / 455 | 1,459 / 1,752 | 400 / 253 |
| 100% | 173 / 169 | 268 / 167 | 2,756 / 3,206 | 422 / 1,003 |

Throughput rose about 40–90% where it moved (the second all-hot run was flat), and p99 latency fell 2.5–7× in every row. Holds are not batched, so they stay well below batched transfers; each operation is also two transactions.

## Possible next steps

- The same short-critical-section design for the hold path.
- Batch reversals and holds too.
- For accounts hotter than one batch per commit can serve, split the balance into several sub-balance rows, or apply credits asynchronously (safe for incoming money because the cached balance can only lag low).
