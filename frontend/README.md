# SmartBank — React Frontend

## Requirements
- Node.js 20.19+ or 22.12+
- SmartBank Spring Boot API on http://localhost:8081

## Run (PowerShell)
```powershell
cd frontend
npm install
npm run dev
```
Open http://localhost:5173. Vite proxies `/api` to Spring Boot, avoiding browser CORS changes during development.

Login with `demo.a@smartbank.local` and the demo password configured by your backend.

## Features
- Login (JWT kept in React memory, not localStorage; refreshing the page logs out).
- Accounts with available balance and pending amounts from holds.
- Deposits and withdrawals; transfers by account number with a review step and printable receipt.
- **Holds** (sidebar → Holds): place a hold on another account by account number, with an expiry; see the posted balance, available balance, money reserved by your holds and money held for you; capture incoming holds in full or in part, or release them; follow outgoing holds you placed.
- Transaction history with type filter and search.
- Every money-moving request sends an idempotency key, and retrying the same failed action reuses it, so a request that reached the server is not repeated.

## Notes
- The backend enforces ownership, available funds and every money-movement rule; the UI checks only for convenience.
- Below 520 px wide the sidebar is hidden, so pages other than Overview cannot be reached on a phone yet.
- This is a development scaffold, not production banking software. Do not commit demo credentials, signing secrets, or customer data.
