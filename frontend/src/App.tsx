import { useCallback, useEffect, useRef, useState } from "react";
import {
    Landmark,
    LogOut,
    RefreshCw,
    Wallet,
    ArrowDownLeft,
    ArrowUpRight,
    History,
    ShieldCheck,
    Clock,
} from "lucide-react";
import Holds from "./Holds";
import {
    accounts,
    deposit,
    login,
    transactions,
    withdraw,
    transferByAccountNumber,
    type Account,
    type Transaction,
    type TransferReceipt
} from './api';
const money = (amount: number, currency: string) =>
    new Intl.NumberFormat("en-CA", { style: "currency", currency }).format(
        amount,
    );
const formatDate = (value: string): string => {
    if (!value || value === "—") {
        return "—";
    }

    const date = new Date(value);

    if (Number.isNaN(date.getTime())) {
        return value;
    }

    return new Intl.DateTimeFormat("en-CA", {
        timeZone: "America/Toronto",
        year: "numeric",
        month: "short",
        day: "numeric",
        hour: "numeric",
        minute: "2-digit",
        hour12: true,
    }).format(date);
};
export default function App() {
    const [token, setToken] = useState<string | null>(null);
    const [email, setEmail] = useState("demo.a@smartbank.local");
    const [password, setPassword] = useState("");
    const [items, setItems] = useState<Account[]>([]);
    const [selected, setSelected] = useState("");
    const [history, setHistory] = useState<Transaction[]>([]);
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");
    const [action, setAction] = useState<"deposit" | "withdraw">("deposit");
    const [amount, setAmount] = useState("");
    const [description, setDescription] = useState("");
    const [destination, setDestination] = useState('');
    const [transferAmount, setTransferAmount] = useState('');
    type TransferDraft = { fromAccountId: string; fromAccountNumber: string; toAccountNumber: string; amount: number; currency: string; requestKey: string };
    const [transferDraft, setTransferDraft] = useState<TransferDraft | null>(null);
    const [transferReceipt, setTransferReceipt] = useState<{ transaction: TransferReceipt; from: string; to: string } | null>(null);
    const [transferUncertain, setTransferUncertain] = useState(false);
    const transferInFlight = useRef(false);
    const pendingMoneyAction = useRef<{ action: string; accountId: string; amount: string; requestKey: string } | null>(null);
    const [page, setPage] = useState<"overview" | "transactions" | "holds">("overview");
    const [filter, setFilter] = useState("all");
    const [search, setSearch] = useState("");
    const active = items.find((a) => a.id === selected);
    const load = useCallback(async (t: string) => {
        setLoading(true);
        setError("");
        try {
            const list = await accounts(t);
            setItems(list);
            setSelected((prev) =>
                list.some((a) => a.id === prev) ? prev : (list[0]?.id ?? ""),
            );
        } catch (e) {
            setError((e as Error).message);
            if ((e as Error).message.includes("401")) setToken(null);
        } finally {
            setLoading(false);
        }
    }, []);
    useEffect(() => {
        if (token) void load(token);
    }, [token, load]);
    useEffect(() => {
        if (!token || !selected) {
            setHistory([]);
            return;
        }
        let cancelled = false;
        transactions(token, selected)
            .then((v) => {
                if (!cancelled) setHistory(v);
            })
            .catch((e) => {
                if (!cancelled) setError((e as Error).message);
            });
        return () => {
            cancelled = true;
        };
    }, [token, selected, items]);
    async function submitLogin(e: React.FormEvent) {
        e.preventDefault();
        setLoading(true);
        setError("");
        try {
            const r = await login(email, password);
            setToken(r.accessToken);
            setPassword("");
        } catch (e) {
            setError((e as Error).message);
        } finally {
            setLoading(false);
        }
    }
    async function submitAction(e: React.FormEvent) {
        e.preventDefault();
        if (!token || !selected) return;
        const value = Number(amount);
        if (
            !Number.isFinite(value) ||
            value <= 0 ||
            !/^\d+(\.\d{1,2})?$/.test(amount)
        ) {
            setError("Enter a positive amount with up to two decimal places.");
            return;
        }
        // Reuse the key when the user retries the exact same failed operation, so a
        // request that reached the server before the error is not posted twice.
        const last = pendingMoneyAction.current;
        const requestKey =
            last && last.action === action && last.accountId === selected && last.amount === amount
                ? last.requestKey
                : crypto.randomUUID();
        pendingMoneyAction.current = { action, accountId: selected, amount, requestKey };
        setLoading(true);
        setError("");
        try {
            if (action === "deposit")
                await deposit(token, selected, value, description, requestKey);
            else await withdraw(token, selected, value, description, requestKey);
            pendingMoneyAction.current = null;
            setAmount("");
            setDescription("");
            await load(token);
        } catch (e) {
            setError((e as Error).message);
        } finally {
            setLoading(false);
        }
    }
    function submitTransfer(e: React.FormEvent) {
        e.preventDefault();
        if (!token || !active || loading || transferDraft) return;
        const value = Number(transferAmount);
        const target = destination.trim();
        if (!/^\d+(\.\d{1,2})?$/.test(transferAmount) || !Number.isFinite(value) || value <= 0) {
            setError('Enter a positive amount with up to two decimal places.');
            return;
        }
        if (!target) { setError('Enter a destination account number.'); return; }
        if (active.accountNumber.toUpperCase() === target.toUpperCase()) {
            setError('Source and destination accounts must differ.');
            return;
        }
        if (value > Number(active.availableBalance ?? active.balance)) {
            setError('The amount exceeds your available balance.');
            return;
        }
        setError('');
        setTransferReceipt(null);
        setTransferUncertain(false);
        setTransferDraft({
            fromAccountId: active.id,
            fromAccountNumber: active.accountNumber,
            toAccountNumber: target,
            amount: value,
            currency: active.currency,
            requestKey: crypto.randomUUID()
        });
    }
    async function confirmTransfer() {
        if (!token || !transferDraft || transferInFlight.current) return;
        transferInFlight.current = true;
        const draft = transferDraft;
        setLoading(true);
        setError('');
        try {
            const receipt = await transferByAccountNumber(
                token, draft.fromAccountId, draft.toAccountNumber, draft.amount, draft.requestKey
            );
            setTransferReceipt({ transaction: receipt, from: draft.fromAccountNumber, to: draft.toAccountNumber });
            setTransferDraft(null);
            setTransferUncertain(false);
            setDestination('');
            setTransferAmount('');
            await load(token);
        } catch (e) {
            setTransferUncertain(true);
            setError(`${(e as Error).message}. If the request may have reached the server, check transaction history. Retry here uses the SAME request key.`);
        } finally {
            transferInFlight.current = false;
            setLoading(false);
        }
    }
    function logout() {
        setPage("overview");
        setToken(null);
        setItems([]);
        setHistory([]);
        setSelected("");
        setError("");
        setTransferDraft(null);
        setTransferReceipt(null);
        setTransferUncertain(false);
    }
    if (!token)
        return (
            <main className="login-page">
                <section className="login-card">
                    <div className="brand">
                        <Landmark size={28} /> SmartBank
                    </div>
                    <div className="eyebrow">CUSTOMER PORTAL</div>
                    <h1>Welcome back</h1>
                    <p>Sign in to view your accounts and manage your money.</p>
                    <form onSubmit={submitLogin}>
                        <label>
                            Email address
                            <input
                                autoComplete="username"
                                type="email"
                                required
                                value={email}
                                onChange={(e) => setEmail(e.target.value)}
                            />
                        </label>
                        <label>
                            Password
                            <input
                                autoComplete="current-password"
                                type="password"
                                required
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                                placeholder="Enter your demo password"
                            />
                        </label>
                        {error && (
                            <div className="error" role="alert">
                                {error}
                            </div>
                        )}
                        <button disabled={loading}>
                            {loading ? "Signing in…" : "Sign in securely"}
                        </button>
                    </form>
                    <div className="fine">
                        <ShieldCheck size={16} /> Demo environment · No real
                        funds
                    </div>
                </section>
            </main>
        );
    return (
        <div className="shell">
            <aside>
                <div className="brand">
                    <Landmark size={27} /> SmartBank
                </div>
                <button
                    type="button"
                    className={`nav ${page === "overview" ? "active" : ""}`}
                    onClick={() => setPage("overview")}
                >
                    <Wallet size={19} /> Overview
                </button>
                <button
                    type="button"
                    className={`nav ${page === "transactions" ? "active" : ""}`}
                    onClick={() => setPage("transactions")}
                >
                    <History size={19} /> Transactions
                </button>
                <button
                    type="button"
                    className={`nav ${page === "holds" ? "active" : ""}`}
                    onClick={() => setPage("holds")}
                >
                    <Clock size={19} /> Holds
                </button>
                <div className="side-bottom">
                    <span>Signed in as</span>
                    <strong>{email}</strong>
                    <button className="ghost" onClick={logout}>
                        <LogOut size={17} /> Sign out
                    </button>
                </div>
            </aside>
            <style>{`
                .transfer-details { display:grid; grid-template-columns: 120px minmax(0,1fr); gap: 12px; margin: 20px 0; }
                .transfer-details dt { color:#64748b; }
                .transfer-details dd { margin:0; overflow-wrap:anywhere; }
                .transfer-actions { display:flex; gap:12px; flex-wrap:wrap; }
                .receipt-id { font-family:monospace; font-size:0.9em; }
                @media print {
                    body * { visibility:hidden !important; }
                    .transfer-receipt, .transfer-receipt * { visibility:visible !important; }
                    .transfer-receipt { position:absolute; left:0; top:0; width:100%; box-shadow:none !important; border:0 !important; }
                    .transfer-receipt button { display:none !important; }
                }
            `}</style>
            <main className="content">
                <header>
                    <div>
                        <div className="eyebrow">PERSONAL BANKING</div>
                        <h1>
                            {page === "overview"
                                ? "Your accounts"
                                : page === "holds"
                                    ? "Holds"
                                    : "Transactions"}
                        </h1>
                        <p>
                            {page === "overview"
                                ? "Welcome to your SmartBank dashboard."
                                : page === "holds"
                                    ? "Reserve money now, settle it later."
                                    : "Review activity for your selected account."}
                        </p>
                    </div>
                    <button
                        className="outline"
                        onClick={() => void load(token)}
                        disabled={loading}
                    >
                        <RefreshCw size={16} /> Refresh
                    </button>
                </header>
                {error && (
                    <div className="error" role="alert">
                        {error}
                    </div>
                )}
                {page === "overview" && (
                    <>
                        <div className="summary">
                            <div className="eyebrow">
                                TOTAL AVAILABLE BALANCE
                            </div>
                            <div className="big-balance">
                                {money(
                                    items.reduce(
                                        (s, a) => s + Number(a.availableBalance ?? a.balance),
                                        0,
                                    ),
                                    "CAD",
                                )}
                            </div>
                            <div className="muted">
                                Across {items.length} accessible account(s) ·
                                CAD
                            </div>
                        </div>
                        <h2>My accounts</h2>
                        <div className="account-grid">
                            {items.map((a) => (
                                <button
                                    key={a.id}
                                    onClick={() => setSelected(a.id)}
                                    className={`account-card ${selected === a.id ? "selected" : ""}`}
                                >
                                    <div className="account-top">
                                        <span>{a.accountType}</span>
                                        <Wallet size={21} />
                                    </div>
                                    <strong>
                                        {money(Number(a.availableBalance ?? a.balance), a.currency)}
                                    </strong>
                                    <span>{a.accountNumber}</span>
                                    {(Number(a.pendingDebits) > 0 || Number(a.pendingCredits) > 0) && (
                                        <small className="pending-line">
                                            {Number(a.pendingDebits) > 0 && `${money(Number(a.pendingDebits), a.currency)} reserved by holds`}
                                            {Number(a.pendingDebits) > 0 && Number(a.pendingCredits) > 0 && " · "}
                                            {Number(a.pendingCredits) > 0 && `${money(Number(a.pendingCredits), a.currency)} incoming`}
                                        </small>
                                    )}
                                    <small>{a.ownerName}</small>
                                </button>
                            ))}
                            {items.length === 0 && !loading && (
                                <p>No accounts are available.</p>
                            )}
                        </div>
                        {active && (
                            <div className="lower">
                                <section className="panel">
                                    <h2>Move money</h2>
                                    <p>
                                        Deposit or withdraw from your selected
                                        account.
                                    </p>
                                    <div className="tabs">
                                        <button
                                            className={
                                                action === "deposit"
                                                    ? "chosen"
                                                    : ""
                                            }
                                            onClick={() => setAction("deposit")}
                                        >
                                            <ArrowDownLeft size={16} /> Deposit
                                        </button>
                                        <button
                                            className={
                                                action === "withdraw"
                                                    ? "chosen"
                                                    : ""
                                            }
                                            onClick={() =>
                                                setAction("withdraw")
                                            }
                                        >
                                            <ArrowUpRight size={16} /> Withdraw
                                        </button>
                                    </div>
                                    <form onSubmit={submitAction}>
                                        <label>
                                            Amount (CAD)
                                            <input
                                                type="number"
                                                min="0.01"
                                                step="0.01"
                                                required
                                                value={amount}
                                                onChange={(e) =>
                                                    setAmount(e.target.value)
                                                }
                                                placeholder="0.00"
                                            />
                                        </label>
                                        <label>
                                            Description
                                            <input
                                                value={description}
                                                onChange={(e) =>
                                                    setDescription(
                                                        e.target.value,
                                                    )
                                                }
                                                placeholder="Optional note"
                                            />
                                        </label>
                                        <button disabled={loading}>
                                            {loading
                                                ? "Processing…"
                                                : action === "deposit"
                                                    ? "Make deposit"
                                                    : "Make withdrawal"}
                                        </button>
                                    </form>
                                    <p className="fine">
                                        Only use demo accounts. Backend
                                        validates ownership and available funds.
                                    </p>
                                </section>
                                <section className="panel">
                                    <h2>Recent transactions</h2>
                                    <p>Activity for {active.accountNumber}</p>
                                    <div className="tx-list">
                                        {history.length === 0 ? (
                                            <div className="empty">
                                                No transactions to display.
                                            </div>
                                        ) : (
                                            history.slice(0, 12).map((t, i) => (
                                                <div className="tx" key={i}>
                                                    <span>
                                                        {String(
                                                            t.description ??
                                                            t.type ??
                                                            "Transaction",
                                                        )}
                                                    </span>
                                                    <strong>
                                                        {t.amount !== undefined
                                                            ? money(
                                                                Number(
                                                                    t.amount,
                                                                ),
                                                                active.currency,
                                                            )
                                                            : "—"}
                                                    </strong>
                                                </div>
                                            ))
                                        )}
                                    </div>
                                </section>
                            </div>
                        )}
                        {active && (
                            <section className="panel" style={{ marginTop: 20 }}>
                                <h2>Transfer Money</h2>
                                <p>Send money from {active.accountNumber} to another demo account.</p>
                                <form onSubmit={submitTransfer}>
                                    <label>From account
                                        <input value={`${active.accountNumber} (${active.currency})`} disabled />
                                    </label>
                                    <label>Destination account number
                                        <input required value={destination} disabled={!!transferDraft || loading}
                                            onChange={e => setDestination(e.target.value)} placeholder="e.g. DEMO-10001" />
                                    </label>
                                    <label>Amount ({active.currency})
                                        <input type="number" min="0.01" step="0.01" required
                                            value={transferAmount} disabled={!!transferDraft || loading}
                                            onChange={e => setTransferAmount(e.target.value)} placeholder="0.00" />
                                    </label>
                                    <button type="submit" disabled={loading || !!transferDraft}>Review transfer</button>
                                </form>
                                <p className="fine">Demo transfers only. The backend validates ownership and available funds.</p>
                            </section>
                        )}
                        {transferDraft && (
                            <section className="panel" style={{ marginTop: 20 }} aria-label="Transfer confirmation">
                                <h2>Confirm your transfer</h2>
                                <p>Review these details before sending. No money has moved yet.</p>
                                <dl className="transfer-details">
                                    <dt>From</dt><dd>{transferDraft.fromAccountNumber}</dd>
                                    <dt>To</dt><dd>{transferDraft.toAccountNumber}</dd>
                                    <dt>Amount</dt><dd><strong>{money(transferDraft.amount, transferDraft.currency)}</strong></dd>
                                </dl>
                                {transferUncertain && <p role="alert">The previous attempt's outcome is uncertain. Check Transactions before retrying. Retrying uses the same request key.</p>}
                                <div className="transfer-actions">
                                    {!transferUncertain && <button type="button" className="outline" disabled={loading}
                                        onClick={() => setTransferDraft(null)}>Edit / Cancel</button>}
                                    <button type="button" disabled={loading} onClick={() => void confirmTransfer()}>
                                        {loading ? 'Processing…' : transferUncertain ? 'Retry same transfer' : 'Confirm transfer'}
                                    </button>
                                </div>
                            </section>
                        )}
                        {transferReceipt && (
                            <section className="panel transfer-receipt" style={{ marginTop: 20 }} aria-label="Transfer receipt" role="status">
                                <h2>✓ Transfer completed</h2>
                                <p>SmartBank demo transaction receipt</p>
                                <h2>{money(Number(transferReceipt.transaction.amount), transferReceipt.transaction.currency)}</h2>
                                <dl className="transfer-details">
                                    <dt>Status</dt><dd>Completed</dd>
                                    <dt>From</dt><dd>{transferReceipt.from}</dd>
                                    <dt>To</dt><dd>{transferReceipt.to}</dd>
                                    <dt>Date</dt><dd>{formatDate(transferReceipt.transaction.createdAt)}</dd>
                                    <dt>Transaction ID</dt><dd className="receipt-id">{transferReceipt.transaction.id}</dd>
                                </dl>
                                <p className="fine">Demo environment · No real funds</p>
                                <button type="button" className="outline" onClick={() => window.print()}>Print receipt</button>
                            </section>
                        )}
                    </>
                )}
                {page === "holds" && (
                    <Holds
                        token={token}
                        accounts={items}
                        selected={selected}
                        onSelect={setSelected}
                        onChanged={() => load(token)}
                    />
                )}
                {page === "transactions" && (
                    <section className="panel history-page">
                        <div className="history-toolbar">
                            <label>
                                Account
                                <select
                                    value={selected}
                                    onChange={(e) =>
                                        setSelected(e.target.value)
                                    }
                                >
                                    {items.map((a) => (
                                        <option key={a.id} value={a.id}>
                                            {a.accountNumber} · {a.accountType}
                                        </option>
                                    ))}
                                </select>
                            </label>
                            <label>
                                Type
                                <select
                                    value={filter}
                                    onChange={(e) => setFilter(e.target.value)}
                                >
                                    <option value="all">All types</option>
                                    {Array.from(
                                        new Set(
                                            history.map((t) =>
                                                String(t.type ?? "UNKNOWN"),
                                            ),
                                        ),
                                    ).map((t) => (
                                        <option key={t} value={t}>
                                            {t}
                                        </option>
                                    ))}
                                </select>
                            </label>
                            <label>
                                Search
                                <input
                                    value={search}
                                    onChange={(e) => setSearch(e.target.value)}
                                    placeholder="Description or type"
                                />
                            </label>
                        </div>
                        <div className="history-scroll">
                            <table className="history-table">
                                <thead>
                                    <tr>
                                        <th>Date</th>
                                        <th>Type</th>
                                        <th>Description</th>
                                        <th>Amount</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {history
                                        .filter(
                                            (t) =>
                                                (filter === "all" ||
                                                    String(
                                                        t.type ?? "UNKNOWN",
                                                    ) === filter) &&
                                                `${String(t.description ?? "")} ${String(t.type ?? "")}`
                                                    .toLowerCase()
                                                    .includes(
                                                        search.toLowerCase(),
                                                    ),
                                        )
                                        .map((t, i) => (
                                            <tr key={String(t.id ?? i)}>
                                                <td>{formatDate(String(t.createdAt ?? t.timestamp ?? t.occurredAt ?? '—'))}</td>
                                                <td>{String(t.type ?? "—")}</td>
                                                <td>
                                                    {String(
                                                        t.description ?? "—",
                                                    )}
                                                </td>
                                                <td className="amount-cell">
                                                    {t.amount !== undefined
                                                        ? money(
                                                            Number(t.amount),
                                                            active?.currency ??
                                                            "CAD",
                                                        )
                                                        : "—"}
                                                </td>
                                            </tr>
                                        ))}
                                </tbody>
                            </table>
                            {history.length === 0 && (
                                <div className="empty">
                                    No transactions for this account.
                                </div>
                            )}
                        </div>
                    </section>
                )}
                <footer>
                    SmartBank demo · React + Spring Boot · Not a real financial
                    institution
                </footer>
            </main>
        </div>
    );
}
