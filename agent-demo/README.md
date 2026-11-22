# SmartBank agent demo

An [MCP](https://modelcontextprotocol.io) server that lets an AI agent pay through a SmartBank spending mandate, and a demo in which Claude shops with it.

The agent only ever holds the mandate's key. Every payment is decided by the bank, inside the same database transaction that moves the money: within the limits it is paid at once, over them it is held for the account owner's approval or rejected. No tool here can raise a limit, and the system prompt is not what keeps the agent in budget.

## The MCP server

`src/server.ts`, over stdio. Tools:

| Tool | What it does |
| --- | --- |
| `get_spending_limits` | The mandate's account, currency, per-payment and daily limits, what is left today, allowed payees |
| `pay` | `to_account_number`, `amount`, `intent`, `idempotency_key`. Returns `POSTED`, `PENDING_APPROVAL` or `REJECTED` (with a reason). Retrying with the same key never pays twice |
| `get_payment` | One payment, for example to see whether the owner approved it |
| `list_payments` | The agent's recent payments, including rejected ones |

Bank refusals (unknown payee, insufficient funds, revoked key) come back as tool errors the model can read.

Use it from Claude Code with a mandate's key (create one with `POST /api/v1/mandates`):

```bash
claude mcp add smartbank -e SMARTBANK_AGENT_KEY=sbk_agent_... -e SMARTBANK_URL=http://localhost:8081 -- npx tsx /absolute/path/to/agent-demo/src/server.ts
```

## The demo

`src/demo.ts`:

1. Signs in as demo customer A and gives an agent a mandate on DEMO-10001: 50.00 CAD per payment, 120.00 CAD per day, payee DEMO-10002 only, over-limit payments sent for approval.
2. Starts the MCP server with the mandate's key and hands its tools to Claude (`claude-opus-5-5`) through the Anthropic SDK's tool runner (`mcpTools` converts the MCP tools).
3. Asks Claude to buy headphones (45.00), two USB-C cables (24.00) and a monitor (180.00). The first two fit the limits and post; the monitor is over the per-payment limit, so the bank holds it for approval.
4. Shows what awaits customer A's approval and, with `--approve`, approves it. Prints the balance and the mandate's audit trail.

Every tool call and the bank's answer are printed as they happen.

```bash
docker compose -f ../infra/compose.yml up --build -d   # SmartBank on localhost:8081
npm install
export ANTHROPIC_API_KEY=...                           # or `ant auth login`
npm run demo                                           # add `-- --approve` to approve the held payment
```

The demo moves demo money on your local database. If the agent run fails, it revokes the mandate it created; otherwise the mandate expires after a day.

The agent loop runs on your machine (the Messages API's tool runner) because the bank and the MCP server are on `localhost`, which a hosted agent could not reach.
