# SmartBank Sprint 4 — React Frontend

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

## Notes
- JWT is held in React memory, not localStorage. Refreshing the page logs out.
- The UI includes login, account visibility, history, deposit, withdrawal.
- Transfer UI is intentionally deferred until the exact TransferRequest DTO is verified.
- The backend must enforce account ownership and all money-movement rules.
- The displayed transaction properties are provisional; match the real transaction DTO before polishing.
- This is a development scaffold, not production banking software.
- Do not commit demo credentials, signing secrets, or customer data.
