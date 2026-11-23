import type { Account, Transaction } from "./api";

export const money = (amount: number, currency: string) =>
    new Intl.NumberFormat("en-CA", { style: "currency", currency }).format(amount);

/** A signed amount: "+$100.00" for money in, "−$100.00" for money out. */
export const signedMoney = (amount: number, currency: string) =>
    `${amount >= 0 ? "+" : "−"}${money(Math.abs(amount), currency)}`;

export const TYPE_LABEL: Record<string, string> = {
    DEPOSIT: "Deposit",
    WITHDRAWAL: "Withdrawal",
    TRANSFER: "Transfer",
    OPENING_BALANCE: "Opening balance",
    REVERSAL: "Reversal",
    FEE: "Fee",
    FX_TRANSFER: "Currency exchange",
};

export const typeLabel = (type: unknown) => TYPE_LABEL[String(type)] ?? String(type ?? "Transaction");

/** Balances added up per currency, largest first; never adds one currency to another. */
export function totalsByCurrency(accounts: Account[]): { currency: string; total: number }[] {
    const totals = new Map<string, number>();
    for (const a of accounts)
        totals.set(a.currency, (totals.get(a.currency) ?? 0) + Number(a.availableBalance ?? a.balance));
    return [...totals].map(([currency, total]) => ({ currency, total })).sort((x, y) => y.total - x.total);
}

export type Line = {
    label: string;
    /** The other side of the transaction, when there is one. */
    detail: string;
    /** Positive for money into the account, negative for money out. */
    amount: number;
    currency: string;
};

/** How a transaction reads from one account's point of view. */
export function describe(t: Transaction, accountId: string, accounts: Account[]): Line {
    const from = t.fromAccountId as string | null | undefined;
    const to = t.toAccountId as string | null | undefined;
    const incoming = to === accountId;
    const name = (id: string | null | undefined) =>
        id ? accounts.find((a) => a.id === id)?.accountNumber ?? `account …${id.slice(-4)}` : null;
    // An FX transfer's payee receives the bought amount in the other currency.
    const fxIn = t.type === "FX_TRANSFER" && incoming;
    const amount = Number(fxIn ? t.counterAmount : t.amount);
    const currency = String(fxIn ? t.counterCurrency : t.currency);
    const other = incoming ? name(from) : name(to);
    let detail = "";
    if (t.type === "DEPOSIT") detail = "Into this account";
    else if (t.type === "WITHDRAWAL") detail = "Out of this account";
    else if (t.type === "OPENING_BALANCE") detail = "Starting balance";
    else if (t.type === "FEE") detail = "Charged by SmartBank";
    else if (other) detail = `${incoming ? "From" : "To"} ${other}`;
    return { label: typeLabel(t.type), detail, amount: incoming ? amount : -amount, currency };
}

/** A date and time in Toronto, the bank's home time zone: "Nov 22, 2026, 3:04 p.m.". */
export function formatDate(value: unknown): string {
    if (value === null || value === undefined || value === "") return "—";
    const date = new Date(String(value));
    if (Number.isNaN(date.getTime())) return String(value);
    return new Intl.DateTimeFormat("en-CA", {
        timeZone: "America/Toronto",
        year: "numeric",
        month: "short",
        day: "numeric",
        hour: "numeric",
        minute: "2-digit",
        hour12: true,
    }).format(date);
}

/** Money typed by a person: positive, at most two decimal places. Null when it is not. */
export function parseAmount(text: string): number | null {
    if (!/^\d+(\.\d{1,2})?$/.test(text.trim())) return null;
    const value = Number(text);
    return Number.isFinite(value) && value > 0 ? value : null;
}
