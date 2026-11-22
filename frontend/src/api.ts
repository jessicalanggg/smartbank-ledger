/** balance is posted money; availableBalance subtracts outgoing holds; pendingCredits is money held for this account by others. */
export type Account = {
  id: string; accountNumber: string; ownerName: string; accountType: string; currency: string;
  balance: number; availableBalance: number; pendingDebits: number; pendingCredits: number;
};
export type HoldStatus = 'PENDING' | 'POSTED' | 'VOIDED' | 'EXPIRED';
export type Hold = {
  id: string; fromAccountId: string; toAccountId: string; amount: number; currency: string; status: HoldStatus;
  postedAmount: number | null; postedTransactionId: string | null;
  createdAt: string; expiresAt: string; resolvedAt: string | null;
};
export type Transaction = Record<string, unknown>;
export type LoginResponse = { accessToken: string; tokenType: string; expiresIn: number };
const base = '/api/v1';
export async function request<T>(path: string, token: string | null, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(base + path, { ...init, headers });
  if (!response.ok) {
    let detail = ''; try { detail = (await response.text()).slice(0, 200); } catch { /* ignore */ }
    throw new Error(`HTTP ${response.status}${detail ? `: ${detail}` : ''}`);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
export function login(email: string, password: string) { return request<LoginResponse>('/auth/login', null, { method: 'POST', body: JSON.stringify({ email, password }) }); }
export function accounts(token: string) { return request<Account[]>('/accounts', token); }
export function transactions(token: string, id: string) { return request<Transaction[]>(`/accounts/${encodeURIComponent(id)}/transactions`, token); }
export function deposit(token: string, id: string, amount: number, description: string, requestKey: string) { return request<unknown>(`/accounts/${encodeURIComponent(id)}/deposits`, token, { method: 'POST', body: JSON.stringify({ amount, description, requestKey }) }); }
export function withdraw(token: string, id: string, amount: number, description: string, requestKey: string) { return request<unknown>(`/accounts/${encodeURIComponent(id)}/withdrawals`, token, { method: 'POST', body: JSON.stringify({ amount, description, requestKey }) }); }
// Transfer request field names must be verified against your Spring Boot TransferRequest DTO before enabling transfers.
export function transfer(
  token: string,
  fromAccountId: string,
  toAccountId: string,
  amount: number,
  requestKey: string
) {
  return request<unknown>(
    '/transfers',
    token,
    {
      method: 'POST',
      body: JSON.stringify({
        fromAccountId,
        toAccountId,
        amount,
        requestKey
      })
    }
  );
}
export function transferByAccountNumber(
  token: string,
  fromAccountId: string,
  toAccountNumber: string,
  amount: number,
  requestKey: string
) {
  return request<TransferReceipt>(
    '/transfers/by-account-number',
    token,
    {
      method: 'POST',
      body: JSON.stringify({
        fromAccountId,
        toAccountNumber,
        amount,
        requestKey
      })
    }
  );
}
export interface TransferReceipt {
  id: string;
  type: string;
  fromAccountId: string;
  toAccountId: string;
  amount: number;
  currency: string;
  createdAt: string;
}

// Two-phase transfers (holds)
export function holdsForAccount(token: string, accountId: string) {
  return request<Hold[]>(`/accounts/${encodeURIComponent(accountId)}/holds`, token);
}
/** The idempotency key is sent as a header; reuse it to retry the same hold safely. */
export function createHoldByAccountNumber(token: string, fromAccountId: string, toAccountNumber: string,
                                          amount: number, expiresInSeconds: number, idempotencyKey: string) {
  return request<Hold>('/holds/by-account-number', token, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify({ fromAccountId, toAccountNumber, amount, expiresInSeconds }),
  });
}
/** Posts the hold; without an amount, the full held amount. Only the payee can do this. */
export function postHold(token: string, holdId: string, amount?: number) {
  return request<Hold>(`/holds/${encodeURIComponent(holdId)}/post`, token, {
    method: 'POST',
    body: JSON.stringify(amount === undefined ? {} : { amount }),
  });
}
/** Releases the hold. Only the payee can do this. */
export function voidHold(token: string, holdId: string) {
  return request<Hold>(`/holds/${encodeURIComponent(holdId)}/void`, token, { method: 'POST' });
}

// Agent spending mandates: an AI agent pays from one account within limits the bank enforces.
export type MandateStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED';
export type Mandate = {
  id: string; name: string; accountId: string; currency: string;
  perPaymentLimit: number; dailyLimit: number; spentToday: number; remainingToday: number;
  askAboveLimit: boolean; allowedPayees: string[]; keyPrefix: string; status: MandateStatus;
  createdAt: string; expiresAt: string; revokedAt?: string;
};
export type AgentPaymentStatus = 'POSTED' | 'PENDING_APPROVAL' | 'APPROVED' | 'DECLINED' | 'EXPIRED' | 'REJECTED';
export type AgentPayment = {
  id: string; mandateId: string; status: AgentPaymentStatus; reason?: string; payeeAccountNumber: string;
  amount: number; currency: string; intent: string; transactionId?: string; holdId?: string;
  approvalExpiresAt?: string; createdAt: string; decidedAt?: string;
};
export type NewMandate = {
  accountId: string; name: string; perPaymentLimit: string; dailyLimit: string;
  allowedPayeeAccountNumbers: string[]; expiresInDays: number; askAboveLimit: boolean;
};
export function mandates(token: string) { return request<Mandate[]>('/mandates', token); }
/** The response holds the agent's API key; this is the only time it is shown. */
export function createMandate(token: string, mandate: NewMandate) {
  return request<{ mandate: Mandate; apiKey: string }>('/mandates', token, { method: 'POST', body: JSON.stringify(mandate) });
}
export function revokeMandate(token: string, id: string) {
  return request<Mandate>(`/mandates/${encodeURIComponent(id)}/revoke`, token, { method: 'POST' });
}
export function mandatePayments(token: string, id: string) {
  return request<AgentPayment[]>(`/mandates/${encodeURIComponent(id)}/payments`, token);
}
export function awaitingApproval(token: string) { return request<AgentPayment[]>('/agent-payments/awaiting-approval', token); }
export function approveAgentPayment(token: string, id: string) {
  return request<AgentPayment>(`/agent-payments/${encodeURIComponent(id)}/approve`, token, { method: 'POST' });
}
export function declineAgentPayment(token: string, id: string) {
  return request<AgentPayment>(`/agent-payments/${encodeURIComponent(id)}/decline`, token, { method: 'POST' });
}
