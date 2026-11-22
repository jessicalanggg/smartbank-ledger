# How SmartBank works

The design behind each part of the ledger. Start with the [README](../README.md) for the overview.

## Contents

- [What it does](#what-it-does)
- [How the ledger works](#how-the-ledger-works)
- [How FX works](#how-fx-works)
- [How agent spending works](#how-agent-spending-works)
- [How tamper evidence works](#how-tamper-evidence-works)
- [How balance checkpoints work](#how-balance-checkpoints-work)
- [How holds work](#how-holds-work)
- [How linked transfers work](#how-linked-transfers-work)
- [How idempotency keys work](#how-idempotency-keys-work)

## What it does

- **Accounts, deposits, withdrawals and transfers** through a versioned REST API (`/api/v1`), with transaction history per account.
- **Double-entry ledger with a chart of accounts.** Every transaction posts balanced, append-only debit and credit lines to typed accounts (assets, liabilities, equity, revenue), each customer account being its own liability account; fees post to fee revenue. An administrator trial balance, as of any moment, checks debits = credits and Assets = Liabilities + Equity + Revenue − Expenses. PostgreSQL rejects at commit any transaction whose entries do not sum to zero, and rejects any `UPDATE` or `DELETE` of entries or transactions. A reconciliation check proves every cached account balance equals the sum of its entries.
- **Two-phase transfers (holds).** A payer places a hold that reserves money without moving it, the way a card authorization does. The payee then posts it (in full or in part, releasing the rest) or voids it, and unposted holds expire automatically. Each account reports a posted balance, an available balance and pending amounts, and PostgreSQL rejects any change that would make the available balance negative.
- **Transactional outbox and signed webhooks.** Every posting and hold change writes an event in the same database transaction, so an event exists if and only if its change committed. A worker delivers events to subscribed endpoints with HMAC-SHA256 signatures, at least once, with exponential backoff, a dead-letter state, and `SKIP LOCKED` leases so several instances can deliver safely. Webhook URLs are checked against SSRF (no internal or private addresses) at registration and before every delivery; signing secrets are encrypted at rest and can be rotated with an overlap; dead-lettered deliveries can be resent. See [docs/webhooks.md](webhooks.md).
- **Linked transfers.** Up to 20 transfers that post together or not at all, such as "charge the customer, pay the merchant, take a fee". Funds are checked on each account's net change across the group, every leg gets its own ledger entries and event tagged with the group's `linkId`, and the whole group is written in one SQL statement.
- **Reversals.** Mistakes are corrected with a new `REVERSAL` posting that points at the original and moves money back along the same path, never by editing history. Partial reversals are allowed but never exceed the original in total, enforced by a database trigger that locks the original at commit. A payee can refund a transfer; only an administrator can reverse deposits and withdrawals.
- **Tamper-evident history and an audit trail.** Every transaction records who started it. A background job seals committed transactions into a SHA-256 hash chain (each seal covers its transactions, their entries and the previous seal), so any change to sealed history, even with the database's append-only triggers switched off, is detected. Reconciliation verifies the chain on every run.
- **Balance checkpoints and statements.** Each account's running debit and credit totals are checkpointed per day, so a balance at any moment is the latest checkpoint plus the few entries since. Customers get statements with an opening balance, every posting with a running balance, and a closing balance; the administrator trial balance reads through the same checkpoints. Reconciliation re-checks every checkpoint against the entries.
- **Multi-currency with FX.** Accounts in CAD, USD, EUR, GBP or JPY, with amounts limited to each currency's decimals (no ¥10.5). Converting is quote-then-execute: a quote locks the rate and both amounts for 60 seconds and can be used once. An FX transfer is one transaction whose entries balance within each currency through per-currency FX position accounts, and the database rejects any transaction that does not.
- **Spending mandates for AI agents.** A customer gives an agent an API key tied to one account, with a per-payment limit, a daily limit, an optional list of allowed payees and an expiry. Payments within the limits post at once; the limit is checked and used up by one conditional statement in the same transaction as the transfer, so 50 agents racing for the last of a daily budget can never overspend it, and a trigger re-checks the limit. Payments over a limit are rejected, or held until the owner approves or declines them. Revoking a mandate takes effect at once, even for payments already in flight. Every attempt is recorded, and every transfer an agent made names its mandate in the ledger and the hash chain.
- **Atomic money movement.** Each operation runs in one `@Transactional` unit: balance updates, the transaction record and its ledger entries commit together or roll back together.
- **Idempotency keys, scoped per user.** Every deposit, withdrawal, transfer and hold takes an `Idempotency-Key` header (or the `requestKey` body field). The first response, success or business error, is stored and replayed byte-for-byte for retries, marked `Idempotent-Replayed: true`, even when many retries arrive at once. Reusing a key for a different request returns 422. Keys are scoped to the calling user and expire after 24 hours. Posting stays exactly-once even if the server stops between posting and storing the response.
- **Short, deadlock-free locking.** A transfer writes its transaction and ledger entries first, then updates both balances last with atomic statements in UUID order (the debit only succeeds if available funds cover it). Account locks are held only for those final statements and the commit, and opposing transfers (A→B and B→A) cannot deadlock.
- **Few database round trips.** A transfer writes its transaction, ledger entries, outbox event and webhook deliveries in one SQL statement and both balance updates in one batch: 6 round trips instead of 17, measured with injected network delay ([docs/benchmarks.md](benchmarks.md#round-trips-per-operation)).
- **Batched posting (group commit).** Concurrent transfers, deposits, withdrawals and linked transfers are posted together: one database transaction locks every account in the batch once, decides each posting in arrival order (a rejected one does not fail the others), and writes them all in one statement. With every transfer paying one account, that is about 5–6× the throughput and p99 latency under 80 ms instead of hundreds of milliseconds. Callers are answered only after commit. See [docs/benchmarks.md](benchmarks.md#batched-posting-group-commit).
- **Benchmarked hot-account throughput.** When every transfer pays one account, this design sustains about 2.6× the throughput of locking both accounts for the whole transfer, with about 10× lower p99 latency (≈540 vs ≈200 transfers/s, p99 under 300 ms vs ≈3 s). See [docs/benchmarks.md](benchmarks.md).
- **Rate limiting.** Token buckets in PostgreSQL, shared by every app instance, each checked with one atomic statement. Failed logins are limited per email (a successful login gives its token back), and login, registration and unauthenticated requests per IP; authenticated requests per user, with a stricter limit for writes. Refused requests get 429 with `Retry-After`. Unknown emails take as long to reject as wrong passwords, so login timing does not reveal which accounts exist.
- **Authentication and authorization.** Spring Security with stateless JWT (HS256, 30-minute expiry) and BCrypt password hashing. Users can only read, debit or view the history of accounts they own.
- **Database-enforced invariants.** Flyway migrations define `CHECK (balance >= 0)`, positive amounts, valid transaction shapes, a `UNIQUE` request key, balanced ledger postings and append-only history, so the database rejects bad states even if application code is wrong.
- **React frontend** with login, accounts (with available balance and pending amounts), history, deposits, withdrawals, transfers by account number, and holds: place a hold by account number, then capture incoming holds in full or in part, or release them. An **AI agents** page gives an agent a mandate (limits, allowed payees, expiry; the key is shown once), shows each agent's spending today against its daily limit and its full activity, lets the customer approve or decline payments that went over a limit, and revokes agents. Every money-moving action retries safely with the same idempotency key.

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

`accounts.balance` is kept as a cached balance, because transfers lock the account row and check funds against it. The ledger is the record it must agree with, which [`LedgerReconciliation`](../backend/src/main/java/com/smartbank/ledger/LedgerReconciliation.java) verifies. Balances that existed before the ledger (migration `V4`) were posted as opening balances dated before each account's first transaction.

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

## How agent spending works

```
POST /mandates   { accountId, name: "Shopping agent", perPaymentLimit: "50.00", dailyLimit: "100.00",
                   allowedPayeeAccountNumbers?: [...], expiresInDays?: 30, askAboveLimit?: true }
  → { mandate: {...}, apiKey: "sbk_agent_…" }          (the key is shown once; only its SHA-256 is stored)

The agent, with  Authorization: Agent sbk_agent_…  and an Idempotency-Key:
POST /agent/payments { toAccountNumber, amount: "30.00", intent: "Buy headphones" }
  → status POSTED             within both limits: paid now
  → status PENDING_APPROVAL   over a limit: money held until the owner approves or declines
  → status REJECTED           payee not allowed, over a limit when the mandate does not ask, or too many awaiting approval
```

- **The limit and the spend are one statement.** Each payment adds its amount to the mandate's usage for the day (UTC) with an upsert that only applies if the day stays within the daily limit, in the same transaction as the transfer. The upsert locks that day's usage row until commit, so simultaneous payments are counted one after another and can never exceed the limit together. If the transfer fails (for example insufficient funds), the usage rolls back with it. A database trigger also rejects any usage over the limit.
- **Approval reuses holds.** A payment over a limit places a hold on the account, so the money is reserved and an approval cannot fail for lack of funds. Approving posts the hold, which counts against the day's usage; declining releases it; after 24 hours it expires. The payee cannot capture these holds the way it captures ordinary ones.
- **Revocation is immediate.** Payments lock their mandate `FOR SHARE`, and revoking locks it exclusively, so it waits for payments in flight and no payment commits after it. Revoking also declines payments awaiting approval. A mandate's limits never change; to change them, revoke it and create another.
- **Audit trail.** Every attempt, including rejected ones, is recorded with its intent and outcome. Transfers made by an agent carry `agentMandateId`, are initiated by the customer the agent acts for, and are covered by the hash chain. Reconciliation checks that each day's usage equals what was paid, that nothing paid without asking exceeded the limit, and that every agent payment matches its transfer.
- **Demo with a real AI agent:** [agent-demo](../agent-demo/) is an MCP server exposing the agent API as tools (`get_spending_limits`, `pay`, `get_payment`, `list_payments`), and a script in which Claude shops through it: two purchases fit the limits and post, the third is over the per-payment limit and waits for the customer's approval.
- Agent keys are rate-limited per mandate and work only on `/api/v1/agent/**`; customer tokens do not work there. Events `agent_payment.posted`, `.approval_requested`, `.approved`, `.declined` and `.rejected` go to the owner's webhooks.

## How tamper evidence works

```
seal 1: SHA-256(genesis, 1, transactions sealed in 1)        ─┐
seal 2: SHA-256(hash of seal 1, 2, transactions sealed in 2)  ├─ each seal covers its transactions, their
seal 3: SHA-256(hash of seal 2, 3, transactions sealed in 3) ─┘  ledger entries, and the previous seal
```

- Every few seconds the sealer ([`LedgerSealer`](../backend/src/main/java/com/smartbank/ledger/LedgerSealer.java)) takes the committed transactions not yet sealed and appends one seal over them. Each transaction is hashed with all its fields (type, accounts, amount, request key, time, initiator, links) and all its ledger entries, in a fixed text form.
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
