import { useCallback, useEffect, useState } from "react";
import { Bot, Check, ChevronDown, ChevronUp, Copy, KeyRound, ShieldAlert, X } from "lucide-react";
import {
    approveAgentPayment,
    awaitingApproval,
    createMandate,
    declineAgentPayment,
    mandatePayments,
    mandates as listMandates,
    revokeMandate,
    type Account,
    type AgentPayment,
    type AgentPaymentStatus,
    type Mandate,
} from "./api";

const money = (amount: number, currency: string) =>
    new Intl.NumberFormat("en-CA", { style: "currency", currency }).format(amount);

const when = (value?: string) =>
    value
        ? new Intl.DateTimeFormat("en-CA", {
              timeZone: "America/Toronto",
              month: "short",
              day: "numeric",
              hour: "numeric",
              minute: "2-digit",
          }).format(new Date(value))
        : "—";

const validAmount = (text: string) => /^\d+(\.\d{1,2})?$/.test(text) && Number(text) > 0;

const DURATIONS = [
    { label: "1 day", days: 1 },
    { label: "7 days", days: 7 },
    { label: "30 days", days: 30 },
    { label: "90 days", days: 90 },
];

const PAYMENT_LABEL: Record<AgentPaymentStatus, string> = {
    POSTED: "Paid",
    PENDING_APPROVAL: "Needs approval",
    APPROVED: "Approved",
    DECLINED: "Declined",
    EXPIRED: "Expired",
    REJECTED: "Blocked",
};

const PAYMENT_BADGE: Record<AgentPaymentStatus, string> = {
    POSTED: "posted",
    APPROVED: "posted",
    PENDING_APPROVAL: "pending",
    DECLINED: "voided",
    EXPIRED: "expired",
    REJECTED: "rejected",
};

type Props = {
    token: string;
    accounts: Account[];
    /** Reloads balances after a decision moves or releases money. */
    onChanged: () => Promise<void>;
};

/**
 * Spending mandates for AI agents. The customer gives an agent a key tied to one
 * account with limits; the bank pays within them, holds anything over them for the
 * customer's decision here, and records every attempt.
 */
export default function Mandates({ token, accounts, onChanged }: Props) {
    const [mandates, setMandates] = useState<Mandate[]>([]);
    const [waiting, setWaiting] = useState<AgentPayment[]>([]);
    const [activity, setActivity] = useState<Record<string, AgentPayment[]>>({});
    const [open, setOpen] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const [notice, setNotice] = useState("");
    const [newKey, setNewKey] = useState<{ id: string; name: string; key: string } | null>(null);
    const [copied, setCopied] = useState(false);

    const [accountId, setAccountId] = useState(accounts[0]?.id ?? "");
    const [name, setName] = useState("");
    const [perPayment, setPerPayment] = useState("");
    const [daily, setDaily] = useState("");
    const [payees, setPayees] = useState("");
    const [days, setDays] = useState(30);
    const [ask, setAsk] = useState(true);

    const account = accounts.find((a) => a.id === accountId);
    const numberOf = (id: string) => accounts.find((a) => a.id === id)?.accountNumber ?? `…${id.slice(-6)}`;
    const agentName = (mandateId: string) => mandates.find((m) => m.id === mandateId)?.name ?? "An agent";

    useEffect(() => {
        if (!accountId && accounts[0]) setAccountId(accounts[0].id);
    }, [accounts, accountId]);

    const loadActivity = useCallback(
        async (id: string) => {
            const payments = await mandatePayments(token, id);
            setActivity((current) => ({ ...current, [id]: payments }));
        },
        [token],
    );

    const refresh = useCallback(async () => {
        try {
            const [m, w] = await Promise.all([listMandates(token), awaitingApproval(token)]);
            setMandates(m);
            setWaiting(w);
            if (open) await loadActivity(open);
        } catch (e) {
            setError((e as Error).message);
        }
    }, [token, open, loadActivity]);

    // The header's Refresh reloads the accounts; reload mandates along with them.
    useEffect(() => {
        void refresh();
    }, [accounts, refresh]);

    async function run(action: () => Promise<unknown>, success: string, movesMoney: boolean) {
        setBusy(true);
        setError("");
        setNotice("");
        try {
            await action();
            setNotice(success);
            await Promise.all([refresh(), movesMoney ? onChanged() : Promise.resolve()]);
        } catch (e) {
            setError((e as Error).message);
            await refresh();
        } finally {
            setBusy(false);
        }
    }

    async function create(e: React.FormEvent) {
        e.preventDefault();
        if (!account) return;
        if (!name.trim()) return setError("Give the agent a name you will recognize.");
        if (!validAmount(perPayment) || !validAmount(daily))
            return setError("Enter limits as positive amounts with up to two decimal places.");
        if (Number(daily) < Number(perPayment)) return setError("The daily limit must be at least the per-payment limit.");
        const allowed = payees
            .split(/[\s,]+/)
            .map((p) => p.trim())
            .filter(Boolean);
        setCopied(false);
        await run(async () => {
            const created = await createMandate(token, {
                accountId: account.id,
                name: name.trim(),
                perPaymentLimit: perPayment,
                dailyLimit: daily,
                allowedPayeeAccountNumbers: allowed,
                expiresInDays: days,
                askAboveLimit: ask,
            });
            setNewKey({ id: created.mandate.id, name: created.mandate.name, key: created.apiKey });
            setName("");
            setPerPayment("");
            setDaily("");
            setPayees("");
        }, `Created a mandate for ${name.trim()}.`, false);
    }

    async function copyKey() {
        if (!newKey) return;
        try {
            await navigator.clipboard.writeText(newKey.key);
            setCopied(true);
        } catch {
            setError("Could not copy; select the key and copy it by hand.");
        }
    }

    function revoke(m: Mandate) {
        if (!window.confirm(`Revoke ${m.name}? Its key stops working at once and payments waiting for approval are declined.`))
            return;
        if (newKey?.id === m.id) setNewKey(null);  // its key is useless now
        void run(() => revokeMandate(token, m.id), `Revoked ${m.name}. Its key no longer works.`, true);
    }

    function decide(p: AgentPayment, approve: boolean) {
        void run(
            () => (approve ? approveAgentPayment(token, p.id) : declineAgentPayment(token, p.id)),
            approve
                ? `Paid ${money(Number(p.amount), p.currency)} to ${p.payeeAccountNumber}.`
                : `Declined; ${money(Number(p.amount), p.currency)} is available again.`,
            true,
        );
    }

    async function toggle(id: string) {
        if (open === id) return setOpen(null);
        setOpen(id);
        try {
            await loadActivity(id);
        } catch (e) {
            setError((e as Error).message);
        }
    }

    const sorted = [...mandates].sort(
        (a, b) => Number(a.status !== "ACTIVE") - Number(b.status !== "ACTIVE") || b.createdAt.localeCompare(a.createdAt),
    );

    return (
        <div className="holds-page">
            {error && <div className="error" role="alert">{error}</div>}
            {notice && <div className="notice" role="status">{notice}</div>}

            <section className={`panel ${waiting.length ? "approval-panel" : ""}`}>
                <h2>
                    <ShieldAlert size={18} /> Waiting for your approval
                </h2>
                <p>
                    Payments your agents tried that went over a limit. The money is set aside until you decide; if you
                    do nothing, it is released when the request expires.
                </p>
                <div className="hold-list">
                    {waiting.length === 0 && <div className="empty">Nothing is waiting for you.</div>}
                    {waiting.map((p) => (
                        <div className="hold-row" key={p.id}>
                            <div className="hold-main">
                                <div>
                                    <strong>{money(Number(p.amount), p.currency)}</strong>
                                    <span className="hold-party">
                                        {agentName(p.mandateId)} → {p.payeeAccountNumber}
                                    </span>
                                </div>
                                <span className="badge pending">Needs approval</span>
                            </div>
                            <div className="intent">“{p.intent}”</div>
                            <div className="hold-meta">
                                {p.reason} · decide by {when(p.approvalExpiresAt)}
                            </div>
                            <div className="hold-actions">
                                <button type="button" className="primary-small" disabled={busy} onClick={() => decide(p, true)}>
                                    <Check size={15} /> Approve and pay
                                </button>
                                <button type="button" className="outline danger" disabled={busy} onClick={() => decide(p, false)}>
                                    <X size={15} /> Decline
                                </button>
                            </div>
                        </div>
                    ))}
                </div>
            </section>

            <div className="lower">
                <section className="panel">
                    <h2>
                        <KeyRound size={18} /> Give an agent a mandate
                    </h2>
                    <p>
                        An AI agent gets a key that can pay from one account, within limits the bank enforces on every
                        payment. Nothing the agent says can raise them.
                    </p>
                    {newKey && (
                        <div className="key-reveal" role="status">
                            <strong>API key for {newKey.name}</strong>
                            <p>Copy it now: it is shown only once, and SmartBank stores only a fingerprint of it.</p>
                            <code>{newKey.key}</code>
                            <div className="hold-actions">
                                <button type="button" className="primary-small" onClick={() => void copyKey()}>
                                    <Copy size={15} /> {copied ? "Copied" : "Copy key"}
                                </button>
                                <button type="button" className="outline" onClick={() => setNewKey(null)}>
                                    Done
                                </button>
                            </div>
                        </div>
                    )}
                    <form onSubmit={create}>
                        <label>
                            Pays from
                            <select value={accountId} onChange={(e) => setAccountId(e.target.value)} disabled={busy}>
                                {accounts.map((a) => (
                                    <option key={a.id} value={a.id}>
                                        {a.accountNumber} · {a.currency} · {money(Number(a.availableBalance), a.currency)} available
                                    </option>
                                ))}
                            </select>
                        </label>
                        <label>
                            Agent name
                            <input value={name} onChange={(e) => setName(e.target.value)} maxLength={100}
                                placeholder="e.g. Shopping assistant" disabled={busy} required />
                        </label>
                        <div className="form-pair">
                            <label>
                                Per payment ({account?.currency ?? ""})
                                <input type="number" min="0.01" step="0.01" value={perPayment}
                                    onChange={(e) => setPerPayment(e.target.value)} placeholder="50.00" disabled={busy} required />
                            </label>
                            <label>
                                Per day ({account?.currency ?? ""})
                                <input type="number" min="0.01" step="0.01" value={daily}
                                    onChange={(e) => setDaily(e.target.value)} placeholder="120.00" disabled={busy} required />
                            </label>
                        </div>
                        <label>
                            Only pay these accounts <span className="optional">(optional)</span>
                            <input value={payees} onChange={(e) => setPayees(e.target.value)}
                                placeholder="e.g. DEMO-10002; leave empty for any" disabled={busy} />
                        </label>
                        <label>
                            Expires after
                            <select value={days} onChange={(e) => setDays(Number(e.target.value))} disabled={busy}>
                                {DURATIONS.map((d) => (
                                    <option key={d.days} value={d.days}>{d.label}</option>
                                ))}
                            </select>
                        </label>
                        <label className="check">
                            <input type="checkbox" checked={ask} onChange={(e) => setAsk(e.target.checked)} disabled={busy} />
                            Ask me before paying anything over a limit (otherwise it is blocked)
                        </label>
                        <button disabled={busy || !account}>{busy ? "Working…" : "Create mandate"}</button>
                    </form>
                </section>

                <section className="panel">
                    <h2>
                        <Bot size={18} /> Your agents
                    </h2>
                    <p>Today's spending resets at midnight UTC. Revoking takes effect at once.</p>
                    <div className="hold-list">
                        {sorted.length === 0 && <div className="empty">You have not given any agent a mandate yet.</div>}
                        {sorted.map((m) => {
                            const spent = Number(m.spentToday);
                            const share = Math.min(100, (spent / Number(m.dailyLimit)) * 100);
                            return (
                                <div className={`hold-row ${m.status !== "ACTIVE" ? "inactive" : ""}`} key={m.id}>
                                    <div className="hold-main">
                                        <div>
                                            <strong>{m.name}</strong>
                                            <span className="hold-party">from {numberOf(m.accountId)}</span>
                                        </div>
                                        <span className={`badge ${m.status.toLowerCase()}`}>
                                            {m.status === "ACTIVE" ? "Active" : m.status === "REVOKED" ? "Revoked" : "Expired"}
                                        </span>
                                    </div>
                                    <div className="spend" aria-label={`Spent ${spent} of ${m.dailyLimit} today`}>
                                        <div className="spend-bar">
                                            <div style={{ width: `${share}%` }} className={share >= 100 ? "full" : ""} />
                                        </div>
                                        <span>
                                            {money(spent, m.currency)} of {money(Number(m.dailyLimit), m.currency)} today
                                        </span>
                                    </div>
                                    <div className="hold-meta">
                                        Up to {money(Number(m.perPaymentLimit), m.currency)} per payment ·{" "}
                                        {m.allowedPayees.length ? `only ${m.allowedPayees.join(", ")}` : "any payee"} ·{" "}
                                        {m.askAboveLimit ? "asks you above limits" : "blocks above limits"}
                                    </div>
                                    <div className="hold-meta">
                                        Key {m.keyPrefix}… ·{" "}
                                        {m.status === "REVOKED" ? `revoked ${when(m.revokedAt)}` : `expires ${when(m.expiresAt)}`}
                                    </div>
                                    <div className="hold-actions">
                                        <button type="button" className="outline" onClick={() => void toggle(m.id)}>
                                            {open === m.id ? <ChevronUp size={15} /> : <ChevronDown size={15} />} Activity
                                        </button>
                                        {m.status === "ACTIVE" && (
                                            <button type="button" className="outline danger" disabled={busy} onClick={() => revoke(m)}>
                                                <X size={15} /> Revoke
                                            </button>
                                        )}
                                    </div>
                                    {open === m.id && (
                                        <div className="activity">
                                            {(activity[m.id] ?? []).length === 0 && (
                                                <div className="empty">This agent has not tried to pay anything yet.</div>
                                            )}
                                            {(activity[m.id] ?? []).map((p) => (
                                                <div className="activity-row" key={p.id}>
                                                    <div>
                                                        <span className="intent">{p.intent}</span>
                                                        <span className="hold-meta">
                                                            {p.payeeAccountNumber} · {when(p.createdAt)}
                                                            {p.reason && p.status !== "POSTED" ? ` · ${p.reason}` : ""}
                                                        </span>
                                                    </div>
                                                    <div className="activity-right">
                                                        <strong>{money(Number(p.amount), p.currency)}</strong>
                                                        <span className={`badge ${PAYMENT_BADGE[p.status]}`}>{PAYMENT_LABEL[p.status]}</span>
                                                    </div>
                                                </div>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            );
                        })}
                    </div>
                </section>
            </div>
        </div>
    );
}
