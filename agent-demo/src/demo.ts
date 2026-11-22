// A Claude agent shopping with a SmartBank spending mandate.
//
// 1. As demo customer A, give an agent a mandate on account DEMO-10001: at most
//    50.00 CAD per payment and 120.00 CAD per day, paying only DEMO-10002 (the
//    store); anything over a limit is sent to the customer for approval.
// 2. Start the MCP server (src/server.ts) with the mandate's key, and hand its tools
//    to Claude through the Anthropic SDK's tool runner.
// 3. Ask Claude to buy three things. The bank, not the model, decides each payment.
// 4. Show what awaits the customer's approval; with --approve, approve it.
//
// Usage: npm run demo [-- --approve]
// Needs SmartBank running locally (docker compose -f infra/compose.yml up -d) and
// Anthropic credentials (ANTHROPIC_API_KEY, or `ant auth login`).

import Anthropic from "@anthropic-ai/sdk";
import { mcpTools, type MCPCallToolResultLike, type MCPClientLike } from "@anthropic-ai/sdk/helpers/beta/mcp";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js";
import { fileURLToPath } from "node:url";
import path from "node:path";

const BANK = process.env.SMARTBANK_URL ?? "http://localhost:8081";
// The local demo login from infra/compose.yml.
const OWNER_EMAIL = "demo.a@smartbank.local";
const OWNER_PASSWORD = process.env.DEMO_PASSWORD ?? "LocalDemoPass123!";
const WALLET = "11111111-1111-4111-8111-111111111111"; // DEMO-10001, 1,250.00 CAD
const STORE = "DEMO-10002";
const approve = process.argv.includes("--approve");

const dim = (s: string) => `\x1b[2m${s}\x1b[0m`;
const bold = (s: string) => `\x1b[1m${s}\x1b[0m`;
const color = (status: string) =>
  ({ POSTED: "\x1b[32m", APPROVED: "\x1b[32m", PENDING_APPROVAL: "\x1b[33m", REJECTED: "\x1b[31m", DECLINED: "\x1b[31m" })[
    status
  ] ?? "";

// ---- The customer's side (a signed-in user) ----------------------------------------

async function owner(method: string, urlPath: string, token?: string, body?: unknown): Promise<any> {
  const response = await fetch(new URL(urlPath, BANK), {
    method,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const parsed = await response.json().catch(() => null);
  if (!response.ok) throw new Error(`${method} ${urlPath} returned ${response.status}: ${JSON.stringify(parsed)}`);
  return parsed;
}

const { accessToken } = await owner("POST", "/api/v1/auth/login", undefined, {
  email: OWNER_EMAIL,
  password: OWNER_PASSWORD,
});
const balance = async () =>
  (await owner("GET", `/api/v1/accounts/${WALLET}`, accessToken)) as { balance: number; availableBalance: number };

const created = await owner("POST", "/api/v1/mandates", accessToken, {
  accountId: WALLET,
  name: "Claude shopping agent",
  perPaymentLimit: "50.00",
  dailyLimit: "120.00",
  allowedPayeeAccountNumbers: [STORE],
  expiresInDays: 1,
  askAboveLimit: true,
});
const mandateId: string = created.mandate.id;
console.log(bold("Customer A gives the agent a mandate on DEMO-10001"));
console.log(dim(`  50.00 CAD per payment, 120.00 CAD per day, payee ${STORE} only, key ${created.mandate.keyPrefix}…`));
console.log(dim(`  balance ${(await balance()).balance.toFixed(2)} CAD\n`));

// ---- The agent's side: the MCP server holds the key, Claude gets its tools -------------

const here = path.dirname(fileURLToPath(import.meta.url));
const transport = new StdioClientTransport({
  command: process.execPath,
  args: ["--import", "tsx", path.join(here, "server.ts")],
  env: { ...(process.env as Record<string, string>), SMARTBANK_URL: BANK, SMARTBANK_AGENT_KEY: created.apiKey },
});
const mcp = new Client({ name: "smartbank-demo", version: "1.0.0" });
await mcp.connect(transport);

// Print each tool call and the bank's answer as they happen.
// (callTool's declared type also allows a legacy result shape this server never sends.)
const callTools: MCPClientLike = {
  callTool: async (params) => {
    const result = await mcp.callTool(params);
    if (!("content" in result)) throw new Error("Unexpected MCP tool result");
    return result as MCPCallToolResultLike;
  },
};
const tools = mcpTools((await mcp.listTools()).tools, callTools).map((tool) => ({
  ...tool,
  run: async (input: Record<string, unknown>, context?: Parameters<typeof tool.run>[1]) => {
    const args = Object.entries(input)
      .filter(([k]) => k !== "idempotency_key")
      .map(([k, v]) => `${k}=${JSON.stringify(v)}`)
      .join(" ");
    console.log(`${bold("→ " + tool.name)} ${dim(args)}`);
    const output = await tool.run(input, context);
    const text = typeof output === "string" ? output : output.map((b) => ("text" in b ? b.text : "")).join("");
    try {
      const p = JSON.parse(text);
      if (p.status && p.amount !== undefined)
        console.log(`  ${color(p.status)}${p.status}\x1b[0m ${p.amount} ${p.currency}${p.reason ? dim(" · " + p.reason) : ""}`);
      else if (p.remainingToday !== undefined)
        console.log(dim(`  ${p.remainingToday} ${p.currency} left today, ${p.perPaymentLimit} per payment`));
    } catch {
      console.log(`  \x1b[31m${text}\x1b[0m`);
    }
    return output;
  },
}));

const client = new Anthropic();
const request =
  `Northwind Electronics (account ${STORE}) sells wireless headphones for 45.00 CAD, ` +
  `USB-C cables for 12.00 CAD each, and a 27-inch monitor for 180.00 CAD. ` +
  `Please buy me the headphones, two USB-C cables and the monitor.`;
console.log(bold("Customer A asks Claude: ") + request + "\n");

const runner = client.beta.messages.toolRunner({
  model: "claude-opus-5-5",
  max_tokens: 16000,
  output_config: { effort: "medium" },
  // If the model declines on safety grounds, the API retries on a fallback model.
  betas: ["server-side-fallback-2026-07-01"],
  fallbacks: "default",
  max_iterations: 12,
  system:
    "You are a shopping assistant that pays with the user's SmartBank agent mandate. " +
    "Check the spending limits first, then pay for each purchase with its own idempotency key. " +
    "The bank enforces the limits: if a payment is PENDING_APPROVAL, it is waiting for the user, so don't retry it " +
    "or split it into smaller payments to get under a limit. End with a short summary of what was paid and what awaits approval.",
  tools,
  messages: [{ role: "user", content: request }],
});

let final: Anthropic.Beta.BetaMessage | undefined;
try {
  for await (const message of runner) final = message;
} catch (e) {
  // Nothing should keep a working key after a failed run.
  await owner("POST", `/api/v1/mandates/${mandateId}/revoke`, accessToken);
  console.error(`\nThe agent run failed, so the demo revoked its mandate: ${(e as Error).message}`);
  console.error("Set ANTHROPIC_API_KEY (or run `ant auth login`) if the error is about authentication.");
  process.exit(1);
} finally {
  await mcp.close();
}

if (final?.stop_reason === "refusal") {
  console.log("\nClaude declined this request.");
} else if (final) {
  const text = final.content.flatMap((b) => (b.type === "text" ? [b.text] : [])).join("\n");
  console.log("\n" + bold("Claude:") + "\n" + text + "\n");
}

// ---- Back to the customer: what needs a decision -------------------------------------

const waiting: any[] = await owner("GET", "/api/v1/agent-payments/awaiting-approval", accessToken);
const fromThisAgent = waiting.filter((p) => p.mandateId === mandateId);
console.log(bold(`Awaiting customer A's approval: ${fromThisAgent.length}`));
for (const p of fromThisAgent) console.log(`  ${p.amount} ${p.currency} · ${p.intent} ${dim("· " + p.reason)}`);
if (approve) {
  for (const p of fromThisAgent) {
    const decided = await owner("POST", `/api/v1/agent-payments/${p.id}/approve`, accessToken);
    console.log(`  ${color(decided.status)}${decided.status}\x1b[0m ${decided.amount} ${decided.currency} · ${decided.intent}`);
  }
} else if (fromThisAgent.length) {
  console.log(dim("  (run with --approve to approve, or decline it in the API)"));
}

const after = await balance();
console.log(dim(`\nDEMO-10001: balance ${after.balance.toFixed(2)} CAD, available ${after.availableBalance.toFixed(2)} CAD`));
const trail: any[] = await owner("GET", `/api/v1/mandates/${mandateId}/payments`, accessToken);
console.log(bold("Audit trail for this mandate (newest first):"));
for (const p of trail) console.log(`  ${color(p.status)}${p.status.padEnd(16)}\x1b[0m ${String(p.amount).padStart(7)} ${p.currency} · ${p.intent}`);
