// A small client for SmartBank's agent API (/api/v1/agent/**), which an AI agent
// calls with its mandate's key: `Authorization: Agent <key>`.

export class SmartBankError extends Error {
  constructor(readonly status: number, readonly problem: unknown) {
    super(`SmartBank returned ${status}: ${describe(problem)}`);
  }
}

function describe(problem: unknown): string {
  if (problem && typeof problem === "object" && "detail" in problem) return String((problem as { detail: unknown }).detail);
  return JSON.stringify(problem);
}

export class AgentApi {
  constructor(private readonly baseUrl: string, private readonly agentKey: string) {}

  /** The mandate's limits, what is left of today's budget, and allowed payees. */
  mandate(): Promise<unknown> {
    return this.request("GET", "/api/v1/agent/mandate");
  }

  /**
   * Pays from the mandate's account. The bank answers POSTED (paid), PENDING_APPROVAL
   * (over a limit; the owner must approve) or REJECTED (with a reason). Retrying with
   * the same idempotency key never pays twice.
   */
  pay(toAccountNumber: string, amount: string, intent: string, idempotencyKey: string): Promise<unknown> {
    return this.request("POST", "/api/v1/agent/payments", { toAccountNumber, amount, intent }, idempotencyKey);
  }

  payment(id: string): Promise<unknown> {
    return this.request("GET", `/api/v1/agent/payments/${encodeURIComponent(id)}`);
  }

  payments(): Promise<unknown> {
    return this.request("GET", "/api/v1/agent/payments");
  }

  private async request(method: string, path: string, body?: unknown, idempotencyKey?: string): Promise<unknown> {
    const headers: Record<string, string> = { Authorization: `Agent ${this.agentKey}` };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
    const response = await fetch(new URL(path, this.baseUrl), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    const parsed = text ? JSON.parse(text) : null;
    if (!response.ok) throw new SmartBankError(response.status, parsed);
    return parsed;
  }
}
