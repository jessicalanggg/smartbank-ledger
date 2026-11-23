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

/** A failed API call. The message is the server's explanation when it sent one. */
export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

/** The server answers errors with RFC 9457 problem details; show their detail, not the JSON. */
async function problemText(response: Response): Promise<string> {
  let text = '';
  try { text = await response.text(); } catch { /* no body */ }
  try {
    const problem = JSON.parse(text) as { detail?: string; title?: string };
    if (problem.detail || problem.title) return String(problem.detail ?? problem.title);
  } catch { /* not JSON */ }
  if (response.status === 401) return 'Your session has ended. Sign in again.';
  if (response.status === 403) return 'You do not have access to that.';
  if (response.status === 429) return 'Too many requests. Wait a moment and try again.';
  return text ? text.slice(0, 200) : `Request failed (HTTP ${response.status})`;
}
export async function request<T>(path: string, token: string | null, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (token) headers.set('Authorization', `Bearer ${token}`);
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(base + path, { ...init, headers });
  if (!response.ok) throw new ApiError(response.status, await problemText(response));
  if (response.status === 204) return undefined as T;
  // An endpoint that returns nothing (null) sends an empty body.
  const text = await response.text();
  return (text ? JSON.parse(text) : null) as T;
}
export function login(email: string, password: string) { return request<LoginResponse>('/auth/login', null, { method: 'POST', body: JSON.stringify({ email, password }) }); }
export function accounts(token: string) { return request<Account[]>('/accounts', token); }
export function transactions(token: string, id: string) { return request<Transaction[]>(`/accounts/${encodeURIComponent(id)}/transactions`, token); }
export function deposit(token: string, id: string, amount: number, description: string, requestKey: string) { return request<unknown>(`/accounts/${encodeURIComponent(id)}/deposits`, token, { method: 'POST', body: JSON.stringify({ amount, description, requestKey }) }); }
export function withdraw(token: string, id: string, amount: number, description: string, requestKey: string) { return request<unknown>(`/accounts/${encodeURIComponent(id)}/withdrawals`, token, { method: 'POST', body: JSON.stringify({ amount, description, requestKey }) }); }
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

// The ledger behind a transaction, and the bank's books (administrators).
export type JournalLine = { code: string; name: string; type: string; debit: number; credit: number; currency: string };
export type SealInfo = { seq: number; sealedAt: string; signed: boolean; keyId?: string };
/** refundable: for a transfer, what its payee can still send back. */
export type TransactionDetails = { transaction: Transaction; journal: JournalLine[]; seal: SealInfo | null; refundable?: number };
export function transactionDetails(token: string, id: string) {
  return request<TransactionDetails>(`/transactions/${encodeURIComponent(id)}/details`, token);
}
export type AccountBalance = {
  id: string; code: string; name: string; type: string; normalBalance: string; currency: string;
  parentId: string | null; customerAccountId: string | null; debits: number; credits: number; balance: number;
};
export type TrialBalance = {
  currency: string; asOf: string; accounts: AccountBalance[]; totalDebits: number; totalCredits: number;
  byType: { type: string; balance: number }[]; balanced: boolean; accountingEquationHolds: boolean;
};
export function trialBalance(token: string, currency: string) {
  return request<TrialBalance>(`/ledger/trial-balance?currency=${encodeURIComponent(currency)}`, token);
}
export type Seal = { seq: number; prevHash: string; hash: string; transactionCount: number; sealedAt: string; keyId: string | null; signature: string | null };
export type SealVerification = { sealsChecked: number; transactionsChecked: number; firstBrokenSeal: number | null; problem: string | null; headHash: string | null };
export type PublishedKey = { keyId: string; algorithm: string; publicKey: string; current: boolean };
export function sealHead(token: string) { return request<Seal | null>('/ledger/seals/head', token); }
export function verifySeals(token: string) { return request<SealVerification>('/ledger/seals/verify', token); }
export function sealPublicKeys(token: string) { return request<PublishedKey[]>('/ledger/seals/public-keys', token); }

/** The role in the signed-in user's token (CUSTOMER or ADMIN). Display only; the server enforces it. */
export function roleOf(token: string): string {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return String(payload.role ?? 'CUSTOMER');
  } catch {
    return 'CUSTOMER';
  }
}

/** Sends all or part of a received transfer back to its payer. Reuse the key to retry safely. */
export function refund(token: string, transactionId: string, amount: number | undefined, requestKey: string) {
  return request<Transaction>(`/transactions/${encodeURIComponent(transactionId)}/reversals`, token, {
    method: 'POST',
    body: JSON.stringify(amount === undefined ? { requestKey } : { amount, requestKey }),
  });
}

// Statements
export type StatementLine = {
  postedAt: string; transactionId: string; type: string; counterparty: string | null; amount: number; balance: number;
};
export type Statement = {
  accountId: string; accountNumber: string; currency: string; from: string; to: string;
  openingBalance: number; moneyIn: number; moneyOut: number; closingBalance: number;
  lines: StatementLine[]; truncated: boolean;
};
export function statement(token: string, accountId: string, from: Date, to: Date) {
  const range = `from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`;
  return request<Statement>(`/accounts/${encodeURIComponent(accountId)}/statement?${range}`, token);
}

// Foreign exchange: a quote locks the rate briefly; executing it moves the money.
export type FxRate = { id: string; baseCurrency: string; quoteCurrency: string; rate: number; setAt: string };
export type FxQuote = {
  id: string; fromAccountId: string; toAccountId: string; sellAmount: number; sellCurrency: string;
  buyAmount: number; buyCurrency: string; rate: number; expiresAt: string;
};
export function fxRates(token: string) { return request<FxRate[]>('/fx/rates', token); }
export function fxQuote(token: string, fromAccountId: string, toAccountId: string, amount: number) {
  return request<FxQuote>('/fx/quotes', token, { method: 'POST', body: JSON.stringify({ fromAccountId, toAccountId, amount }) });
}
export function fxExecute(token: string, quoteId: string, requestKey: string) {
  return request<Transaction>('/fx/transfers', token, { method: 'POST', body: JSON.stringify({ quoteId, requestKey }) });
}
