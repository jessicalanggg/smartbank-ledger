import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowDownLeft, ArrowUpRight, Check, Clock, X } from "lucide-react";
import {
    createHoldByAccountNumber,
    holdsForAccount,
    postHold,
    voidHold,
    type Account,
    type Hold,
} from "../api";

const money = (amount: number, currency: string) =>
    new Intl.NumberFormat("en-CA", { style: "currency", currency }).format(amount);

const when = (value: string | null) =>
    value
        ? new Intl.DateTimeFormat("en-CA", {
              timeZone: "America/Toronto",
              month: "short",
              day: "numeric",
              hour: "numeric",
              minute: "2-digit",
          }).format(new Date(value))
        : "—";

const EXPIRY_OPTIONS = [
    { label: "1 hour", seconds: 3_600 },
    { label: "1 day", seconds: 86_400 },
    { label: "7 days", seconds: 604_800 },
    { label: "30 days", seconds: 2_592_000 },
];

const STATUS_LABEL: Record<Hold["status"], string> = {
    PENDING: "Pending",
    POSTED: "Captured",
    VOIDED: "Released",
    EXPIRED: "Expired",
};

const validAmount = (text: string) => /^\d+(\.\d{1,2})?$/.test(text) && Number(text) > 0;

type Props = {
    token: string;
    accounts: Account[];
    selected: string;
    onSelect: (id: string) => void;
    /** Reloads balances after a hold changes them. */
    onChanged: () => Promise<void>;
};

/**
 * Two-phase transfers. A payer places a hold that reserves money without moving it;
 * the payee then captures it (in full or in part) or releases it, or it expires.
 */
export default function Holds({ token, accounts, selected, onSelect, onChanged }: Props) {
    const account = accounts.find((a) => a.id === selected);
    const [holds, setHolds] = useState<Hold[]>([]);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [notice, setNotice] = useState("");
    const [toNumber, setToNumber] = useState("");
    const [amount, setAmount] = useState("");
    const [expiry, setExpiry] = useState(604_800);
    const [partial, setPartial] = useState<Record<string, string>>({});
    // Reuse the idempotency key when retrying the exact same failed hold, so a request
    // that reached the server before the error does not reserve the money twice.
    const lastAttempt = useRef<{ fingerprint: string; key: string } | null>(null);

    const ownNumber = (id: string) => accounts.find((a) => a.id === id)?.accountNumber;
    const describe = (id: string) => ownNumber(id) ?? `Account …${id.slice(-6)}`;

    const refresh = useCallback(async () => {
        if (!selected) return;
        try {
            setHolds(await holdsForAccount(token, selected));
        } catch (e) {
            setError((e as Error).message);
        }
    }, [token, selected]);

    useEffect(() => {
        setHolds([]);
        setPartial({});
        void refresh();
    }, [refresh]);

    // The header's Refresh reloads the accounts; reload the holds along with them.
    useEffect(() => {
        void refresh();
    }, [accounts, refresh]);

    async function run(action: () => Promise<unknown>, success: string) {
        setBusy(true);
        setError("");
        setNotice("");
        try {
            await action();
            setNotice(success);
            await Promise.all([refresh(), onChanged()]);
        } catch (e) {
            setError((e as Error).message);
            await refresh();
        } finally {
            setBusy(false);
        }
    }

    async function placeHold(e: React.FormEvent) {
        e.preventDefault();
        if (!account) return;
        const target = toNumber.trim();
        if (!validAmount(amount)) return setError("Enter a positive amount with up to two decimal places.");
        if (!target) return setError("Enter the account number to hold money for.");
        if (target.toUpperCase() === account.accountNumber.toUpperCase())
            return setError("A hold needs a different account to pay.");
        if (Number(amount) > Number(account.availableBalance))
            return setError("The amount exceeds your available balance.");
        const fingerprint = `${account.id}|${target}|${amount}|${expiry}`;
        const key = lastAttempt.current?.fingerprint === fingerprint ? lastAttempt.current.key : crypto.randomUUID();
        lastAttempt.current = { fingerprint, key };
        await run(async () => {
            await createHoldByAccountNumber(token, account.id, target, Number(amount), expiry, key);
            lastAttempt.current = null;
            setToNumber("");
            setAmount("");
        }, `Reserved ${money(Number(amount), account.currency)} for ${target}.`);
    }

    function capture(hold: Hold, part?: string) {
        if (part !== undefined) {
            if (!validAmount(part)) return setError("Enter a positive amount with up to two decimal places.");
            if (Number(part) > Number(hold.amount)) return setError("You can capture at most the held amount.");
        }
        const value = part === undefined ? Number(hold.amount) : Number(part);
        void run(() => postHold(token, hold.id, part === undefined ? undefined : value),
            `Captured ${money(value, hold.currency)}${value < Number(hold.amount) ? "; the rest was released" : ""}.`);
    }

    function release(hold: Hold) {
        void run(() => voidHold(token, hold.id), `Released ${money(Number(hold.amount), hold.currency)}.`);
    }

    if (!account) return <section className="panel history-page"><p>No accounts are available.</p></section>;

    const order = (h: Hold) => (h.status === "PENDING" ? 0 : 1);
    const sorted = [...holds].sort((a, b) => order(a) - order(b) || b.createdAt.localeCompare(a.createdAt));
    const incoming = sorted.filter((h) => h.toAccountId === account.id);
    const outgoing = sorted.filter((h) => h.fromAccountId === account.id);

    return (
        <div className="holds-page">
            <section className="panel">
                <div className="history-toolbar">
                    <label>
                        Account
                        <select value={selected} onChange={(e) => onSelect(e.target.value)}>
                            {accounts.map((a) => (
                                <option key={a.id} value={a.id}>
                                    {a.accountNumber} · {a.accountType}
                                </option>
                            ))}
                        </select>
                    </label>
                </div>
                <dl className="balance-strip">
                    <div>
                        <dt>Posted balance</dt>
                        <dd>{money(Number(account.balance), account.currency)}</dd>
                    </div>
                    <div>
                        <dt>Available to spend</dt>
                        <dd className="strong">{money(Number(account.availableBalance), account.currency)}</dd>
                    </div>
                    <div>
                        <dt>Reserved by your holds</dt>
                        <dd>{money(Number(account.pendingDebits), account.currency)}</dd>
                    </div>
                    <div>
                        <dt>Held for you by others</dt>
                        <dd>{money(Number(account.pendingCredits), account.currency)}</dd>
                    </div>
                </dl>
            </section>

            {error && <div className="error" role="alert">{error}</div>}
            {notice && <div className="notice" role="status">{notice}</div>}

            <div className="lower">
                <section className="panel">
                    <h2>Place a hold</h2>
                    <p>
                        Reserve money for another account without sending it yet, like a card
                        authorization. The other account captures it or releases it; if it does
                        neither, the hold expires and the money is yours again.
                    </p>
                    <form onSubmit={placeHold}>
                        <label>
                            Hold money for account number
                            <input value={toNumber} onChange={(e) => setToNumber(e.target.value)}
                                placeholder="e.g. DEMO-10002" disabled={busy} required />
                        </label>
                        <label>
                            Amount ({account.currency})
                            <input type="number" min="0.01" step="0.01" value={amount}
                                onChange={(e) => setAmount(e.target.value)} placeholder="0.00" disabled={busy} required />
                        </label>
                        <label>
                            Expires after
                            <select value={expiry} onChange={(e) => setExpiry(Number(e.target.value))} disabled={busy}>
                                {EXPIRY_OPTIONS.map((o) => (
                                    <option key={o.seconds} value={o.seconds}>{o.label}</option>
                                ))}
                            </select>
                        </label>
                        <button disabled={busy}>{busy ? "Working…" : "Place hold"}</button>
                    </form>
                    <p className="fine">
                        <Clock size={14} /> Available now: {money(Number(account.availableBalance), account.currency)}
                    </p>
                </section>

                <section className="panel">
                    <h2>
                        <ArrowDownLeft size={18} /> Incoming holds
                    </h2>
                    <p>Money other accounts reserved for {account.accountNumber}. Capture it to receive it, or release it.</p>
                    <div className="hold-list">
                        {incoming.length === 0 && <div className="empty">No incoming holds.</div>}
                        {incoming.map((h) => (
                            <div className="hold-row" key={h.id}>
                                <div className="hold-main">
                                    <div>
                                        <strong>{money(Number(h.amount), h.currency)}</strong>
                                        <span className="hold-party">from {describe(h.fromAccountId)}</span>
                                    </div>
                                    <span className={`badge ${h.status.toLowerCase()}`}>{STATUS_LABEL[h.status]}</span>
                                </div>
                                <div className="hold-meta">
                                    {h.status === "PENDING"
                                        ? `Expires ${when(h.expiresAt)}`
                                        : h.status === "POSTED"
                                            ? `Captured ${money(Number(h.postedAmount), h.currency)} · ${when(h.resolvedAt)}`
                                            : `${STATUS_LABEL[h.status]} ${when(h.resolvedAt)}`}
                                </div>
                                {h.status === "PENDING" && (
                                    <div className="hold-actions">
                                        <button type="button" className="primary-small" disabled={busy} onClick={() => capture(h)}>
                                            <Check size={15} /> Capture all
                                        </button>
                                        <input type="number" min="0.01" step="0.01" placeholder="Part"
                                            aria-label={`Amount to capture of ${money(Number(h.amount), h.currency)}`}
                                            value={partial[h.id] ?? ""} disabled={busy}
                                            onChange={(e) => setPartial({ ...partial, [h.id]: e.target.value })} />
                                        <button type="button" className="outline" disabled={busy || !partial[h.id]}
                                            onClick={() => capture(h, partial[h.id])}>
                                            Capture part
                                        </button>
                                        <button type="button" className="outline danger" disabled={busy} onClick={() => release(h)}>
                                            <X size={15} /> Release
                                        </button>
                                    </div>
                                )}
                            </div>
                        ))}
                    </div>
                </section>
            </div>

            <section className="panel" style={{ marginTop: 22 }}>
                <h2>
                    <ArrowUpRight size={18} /> Your holds on other accounts
                </h2>
                <p>Money reserved from {account.accountNumber}. The other account decides whether to capture it.</p>
                <div className="hold-list">
                    {outgoing.length === 0 && <div className="empty">You have not placed any holds from this account.</div>}
                    {outgoing.map((h) => (
                        <div className="hold-row" key={h.id}>
                            <div className="hold-main">
                                <div>
                                    <strong>{money(Number(h.amount), h.currency)}</strong>
                                    <span className="hold-party">for {describe(h.toAccountId)}</span>
                                </div>
                                <span className={`badge ${h.status.toLowerCase()}`}>{STATUS_LABEL[h.status]}</span>
                            </div>
                            <div className="hold-meta">
                                {h.status === "PENDING"
                                    ? `Reserved · expires ${when(h.expiresAt)}`
                                    : h.status === "POSTED"
                                        ? `Captured ${money(Number(h.postedAmount), h.currency)} · ${when(h.resolvedAt)}`
                                        : `${STATUS_LABEL[h.status]} ${when(h.resolvedAt)} · nothing was taken`}
                            </div>
                        </div>
                    ))}
                </div>
            </section>
        </div>
    );
}
