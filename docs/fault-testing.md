# Fault testing

Two kinds of tests go beyond fixed examples: a seeded simulation of the whole bank against an independent model, and fault injection under load. Both run in the normal test suite (`mvn test`) against real PostgreSQL in Testcontainers.

## Seeded simulation

[`LedgerSimulationTest`](../backend/src/test/java/com/smartbank/fault/LedgerSimulationTest.java) runs the bank from a single seed. The seed decides every request and every fault:

- **Requests:** deposits, withdrawals, fees, transfers (including same-account ones, which must be rejected), linked transfers, reversals, holds and their posts, voids and expiry, exact retries, conflicting reuse of a key, and requests for things that do not exist.
- **Faults:** about 6% of requests **crash** inside their database transaction, after every write and just before commit, so nothing may change; the client sends the request again a few steps later. About 6% **lose their response** after committing; the client retries with the same key and must get the same transaction or hold back, with nothing happening twice.

A small in-memory model, written independently of the service code, predicts every outcome. After every step, the outcome and every balance and pending amount must match it. The books are reconciled every 25 steps and at the end of each run, including the hash chain.

Requests run one at a time, so a seed replays exactly: the test runs its first seed twice and checks the two traces (every step, fault and outcome) are identical. On a mismatch it shrinks the run to the fewest steps that still fail and prints the seed. Reproduce with `-Dsim.seed=<seed>`; widen coverage with `-Dsim.runs=500 -Dsim.length=200`.

**Default run:** 40 seeds of 100 requests each, plus the retries the faults cause, all matching the model.

**Checked against a planted bug.** Changing `voidHold` so it did not release the payee's pending credit made a run fail at step 44 and shrink to a hold and its void.

**What it does not cover:** concurrency. Requests run one at a time, which is what makes a seed replayable; interleavings are covered by the concurrency tests and by the fault injection below, which are not replayable. Time is real, so expiry is driven by calling the expiry jobs with a future moment, and a run never crosses a day boundary.

## Fault injection under load

[`FaultInjectionTest`](../backend/src/test/java/com/smartbank/fault/FaultInjectionTest.java) routes all database traffic through [Toxiproxy](https://github.com/Shopify/toxiproxy), so connections can be cut on command, and runs PostgreSQL with `fsync=on` and `synchronous_commit=on`.

16 client threads send 1,500 transfers between 20 accounts. Like a well-behaved API client, each retries with the **same request key** after any connection error, until the transfer succeeds or is rejected for a business reason.

| Scenario | Fault |
| --- | --- |
| Network cuts | All connections dropped, and new ones refused, for 0.2–0.6 s at 10%, 20% … 80% of the load |
| Database crashes | PostgreSQL killed with `SIGKILL` at 25% and 60% of the load, then restarted; it recovers from its write-ahead log |

After the faults stop, the test requires:

- every transfer a client was told succeeded exists after recovery, with the id the client received;
- no request key posted more than once;
- total money unchanged, and the ledger reconciles;
- every request resolved;
- the PostgreSQL log shows two crash recoveries (`automatic recovery in progress`), proving the restarts were not clean shutdowns.

**Typical run:**

| Scenario | Transfers | Connection errors retried | Lost acknowledgements recovered | Result |
| --- | --- | --- | --- | --- |
| Network cuts | 1,500 succeeded | 80 | 4–8 | Books reconciled |
| Database crashes | 1,500 succeeded | 20 | 0–2 | Books reconciled, 2 crash recoveries |

A **lost acknowledgement** is a transfer that committed while the client saw an error, so the client could not know whether it went through. The retry with the same key returned the original transaction instead of moving money twice. This is the failure idempotency keys exist for, and the test observes it happening in every network run.

**Checked against weakened durability.** With `synchronous_commit=off`, PostgreSQL acknowledges commits before they reach the write-ahead log. The crash test then fails, because a transfer acknowledged to a client is missing after recovery. This shows the durability check catches lost commits.

## Limits

- A `SIGKILL` of the database process is not a power loss or OS crash. Data the kernel had already accepted survives a process kill even without `fsync`. Power-loss durability is not tested here.
- Faults are injected between the application and the database. Application crashes mid-request are equivalent from the database's side (the connection drops) but are not simulated as a JVM kill.
- Only transfers run under fault injection. Holds are covered by the model test and the concurrency tests, not under crashes.
- The model test is sequential; concurrency is covered separately by `ConcurrencyIntegrationTest`.
