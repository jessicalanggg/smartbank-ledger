# Fault testing

Two kinds of tests go beyond fixed examples: model-based randomized sequences, and fault injection under load. Both run in the normal test suite (`mvn test`) against real PostgreSQL in Testcontainers.

## Model-based randomized testing

[`LedgerModelTest`](../backend/src/test/java/com/smartbank/fault/LedgerModelTest.java) generates random sequences of operations:

- deposits, withdrawals and transfers, including same-account transfers, which must be rejected;
- exact retries of earlier requests, and conflicting reuse of a request key;
- hold creation (including retries), full and partial posts, voids, expiry, and operations on holds that do not exist.

A small in-memory model, written independently of the service code, predicts the outcome of every operation (success, rejection, or not found) and every account's balance, pending debits and pending credits. After each operation the real outcome and all balances must match the model; after each sequence the ledger must reconcile.

On a mismatch, the test shrinks the sequence by removing operations while it still fails, and reports the seed and the minimal sequence. Reproduce with `-Dmodel.seed=<seed>`; widen coverage with `-Dmodel.sequences=500 -Dmodel.length=200`.

**Default run:** 60 sequences of 80 operations (4,800 operations), all matching the model.

**Checked against a planted bug.** Changing `voidHold` so it did not release the payee's pending credit made the test fail at step 44 of a sequence and shrink it to the two-operation reproduction:

```
CreateHold[from=0, to=3, amount=68.97, key=…]
VoidHold[holdKey=…]
```

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
