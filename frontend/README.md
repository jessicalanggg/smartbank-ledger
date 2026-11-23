# SmartBank — React Frontend

## Requirements
- Node.js 20.19+ or 22.12+
- SmartBank Spring Boot API on http://localhost:8081

## Run
```bash
cd frontend
npm install
npm run dev
```
Open http://localhost:5173. Vite proxies `/api` to Spring Boot, avoiding browser CORS changes during development.

Sign in as `demo.a@smartbank.local` or `demo.b@smartbank.local` (customers) or `demo.admin@smartbank.local` (administrator), with the demo password configured by your backend.

## Test
```bash
npm test
```
Vitest and React Testing Library in jsdom, with the API faked at `fetch` (`src/test/fakeApi.ts`).

## Features
- Accounts with a total per currency (never added across currencies), available balance and pending amounts from holds.
- Deposits and withdrawals; transfers by account number with a review step and printable receipt.
- **Transactions** with a type filter and search. Selecting one opens its double-entry ledger lines and the signed seal that covers it; the payee of a transfer can refund all or part of it there.
- **Statements**: opening balance, every posting with a running balance, closing balance, for a chosen period; printable.
- **Exchange**: convert between your own accounts in different currencies. The quote holds the rate for 60 seconds, with a countdown; an expired quote cannot be confirmed.
- **Holds**: place a hold on another account by account number, capture incoming holds in full or in part, or release them.
- **Ledger** (administrators): trial balance per currency, the accounting-equation check, and live verification of the signed seal chain.
- Every money-moving request sends an idempotency key, and retrying the same failed action reuses it, so a request that reached the server is not repeated.
- Each page has its own URL (`/transactions`, `/statement`, `/exchange`, `/holds`, `/ledger`); the back button and reloads work. The session is kept in `sessionStorage`, so closing the tab signs you out.
- Toasts for results, loading placeholders and empty states; a bottom tab bar on phones.

## Layout
- `src/App.tsx`: session, data loading and routing.
- `src/pages/`: one file per page. `src/components/`: the layout, forms, the transaction drawer, toasts and placeholders.
- `src/api.ts`: typed API calls; errors carry the server's problem-detail message. `src/format.ts`: money and dates. `src/router.ts`: URLs.

## Notes
- The backend enforces ownership, available funds and every money-movement rule; the UI checks only for convenience.
- This is a development scaffold, not production banking software. Do not commit demo credentials, signing secrets, or customer data.
