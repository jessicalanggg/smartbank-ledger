// An MCP server that lets an AI agent pay through a SmartBank spending mandate.
//
// The agent only ever holds the mandate's key. The bank decides every payment:
// within the mandate's limits it is paid at once, over them it waits for the
// account owner's approval or is rejected. Nothing here can raise a limit.
//
// Configuration (environment):
//   SMARTBANK_AGENT_KEY  the mandate's API key (sbk_agent_...), required
//   SMARTBANK_URL        the bank's base URL, default http://localhost:8081
//
// Runs over stdio, so any MCP client (Claude Code, Claude Desktop, the demo in
// this folder) can start it.

import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { AgentApi, SmartBankError } from "./smartbank.js";

const key = process.env.SMARTBANK_AGENT_KEY;
if (!key) {
  console.error("Set SMARTBANK_AGENT_KEY to a mandate's API key (create one with POST /api/v1/mandates).");
  process.exit(1);
}
const bank = new AgentApi(process.env.SMARTBANK_URL ?? "http://localhost:8081", key);

const server = new McpServer({ name: "smartbank-agent-payments", version: "1.0.0" });

/** Tool results as JSON text; bank refusals come back as tool errors the model can read. */
async function result(call: () => Promise<unknown>) {
  try {
    return { content: [{ type: "text" as const, text: JSON.stringify(await call(), null, 2) }] };
  } catch (e) {
    const text = e instanceof SmartBankError ? e.message : `Could not reach SmartBank: ${(e as Error).message}`;
    return { content: [{ type: "text" as const, text }], isError: true };
  }
}

server.registerTool(
  "get_spending_limits",
  {
    title: "Get spending limits",
    description:
      "Shows this agent's spending mandate: the account it pays from, its currency, the per-payment and daily limits, " +
      "how much has been spent today and how much is left, the payees it may pay (empty means any), and whether " +
      "payments over a limit are sent to the owner for approval. Check this before paying.",
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  () => result(() => bank.mandate()),
);

server.registerTool(
  "pay",
  {
    title: "Pay",
    description:
      "Pays another account from this agent's mandate. The bank checks the mandate's limits and answers with a status: " +
      "POSTED means the money moved; PENDING_APPROVAL means it is over a limit and the money is held until the account " +
      "owner approves or declines it (tell the user; do not retry); REJECTED means it was not allowed, with a reason. " +
      "Use one idempotency key per purchase: retrying with the same key never pays twice.",
    inputSchema: {
      to_account_number: z.string().min(1).max(40).describe("The payee's account number, e.g. DEMO-10002"),
      amount: z
        .string()
        .regex(/^\d+(\.\d{1,2})?$/)
        .describe("The amount in the mandate's currency as a decimal string, e.g. \"45.00\""),
      intent: z.string().min(1).max(500).describe("What the payment is for, in a few words; shown to the account owner"),
      idempotency_key: z
        .string()
        .min(1)
        .max(200)
        .describe("A key unique to this purchase, e.g. \"order-headphones-1\"; reuse it only to retry the same purchase"),
    },
    annotations: { readOnlyHint: false, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  },
  ({ to_account_number, amount, intent, idempotency_key }) =>
    result(() => bank.pay(to_account_number, amount, intent, idempotency_key)),
);

server.registerTool(
  "get_payment",
  {
    title: "Get payment",
    description: "Shows one of this agent's payments, for example whether a payment awaiting approval was approved.",
    inputSchema: { payment_id: z.string().uuid().describe("The payment's id, as returned by pay") },
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  ({ payment_id }) => result(() => bank.payment(payment_id)),
);

server.registerTool(
  "list_payments",
  {
    title: "List payments",
    description: "Lists this agent's most recent payments (newest first), including rejected ones.",
    annotations: { readOnlyHint: true, openWorldHint: false },
  },
  () => result(() => bank.payments()),
);

await server.connect(new StdioServerTransport());
