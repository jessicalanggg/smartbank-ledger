import { useCallback, useEffect, useRef, useState } from "react";
import { BookOpen, Lock, RotateCcw, ShieldCheck, X } from "lucide-react";
import { refund, transactionDetails, type Account, type TransactionDetails } from "../api";
import { describe, money, parseAmount, signedMoney } from "../format";
import { Skeleton } from "./Skeleton";
import { useToast } from "./Toasts";

const when = (value?: string) =>
    value
        ? new Intl.DateTimeFormat("en-CA", {
              timeZone: "America/Toronto",
              dateStyle: "medium",
              timeStyle: "short",
          }).format(new Date(value))
        : "—";

type Props = {
    token: string;
    transactionId: string;
    /** The account the transaction is viewed from, for its direction. */
    accountId: string;
    accounts: Account[];
    onClose: () => void;
    /** Called after a refund changes balances. */
    onChanged?: () => void;
};

/**
 * One transaction as the bank's books record it: the double-entry lines it posted on
 * the chart of accounts, and the seal in the tamper-evident hash chain that covers it.
 */
export default function TransactionDetail({ token, transactionId, accountId, accounts, onClose, onChanged }: Props) {
    const [details, setDetails] = useState<TransactionDetails | null>(null);
    const [error, setError] = useState("");

    const reload = useCallback(() => transactionDetails(token, transactionId), [token, transactionId]);
    useEffect(() => {
        let cancelled = false;
        reload()
            .then((d) => !cancelled && setDetails(d))
            .catch((e) => !cancelled && setError((e as Error).message));
        return () => {
            cancelled = true;
        };
    }, [reload]);

    useEffect(() => {
        const close = (e: KeyboardEvent) => e.key === "Escape" && onClose();
        window.addEventListener("keydown", close);
        return () => window.removeEventListener("keydown", close);
    }, [onClose]);

    const line = details ? describe(details.transaction, accountId, accounts) : null;
    const debits = details?.journal.reduce((s, l) => s + Number(l.debit), 0) ?? 0;
    const credits = details?.journal.reduce((s, l) => s + Number(l.credit), 0) ?? 0;
    const currencies = new Set(details?.journal.map((l) => l.currency));

    return (
        <div className="drawer-backdrop" onClick={onClose}>
            <aside className="drawer" role="dialog" aria-modal="true" aria-label="Transaction details" onClick={(e) => e.stopPropagation()}>
                <div className="drawer-head">
                    <div>
                        <div className="eyebrow">TRANSACTION</div>
                        <h2>{line?.label ?? "Loading…"}</h2>
                    </div>
                    <button type="button" className="icon-button" onClick={onClose} aria-label="Close">
                        <X size={18} />
                    </button>
                </div>
                {error && <div className="error" role="alert">{error}</div>}
                {!details && !error && <Skeleton lines={8} />}
                {details && line && (
                    <>
                        <div className={`drawer-amount ${line.amount >= 0 ? "amount-in" : "amount-out"}`}>
                            {signedMoney(line.amount, line.currency)}
                        </div>
                        <dl className="drawer-facts">
                            <dt>When</dt>
                            <dd>{when(String(details.transaction.createdAt ?? ""))}</dd>
                            {line.detail && (<><dt>Details</dt><dd>{line.detail}</dd></>)}
                            <dt>Transaction ID</dt>
                            <dd className="mono">{String(details.transaction.id)}</dd>
                        </dl>

                        {details.refundable !== undefined &&
                            accounts.some((a) => a.id === details.transaction.toAccountId) && (
                                <Refund
                                    token={token}
                                    transactionId={transactionId}
                                    refundable={Number(details.refundable)}
                                    currency={String(details.transaction.currency)}
                                    onRefunded={() => {
                                        void reload().then(setDetails);
                                        onChanged?.();
                                    }}
                                />
                            )}

                        <h3><BookOpen size={16} /> Ledger entries</h3>
                        <p className="drawer-note">
                            Every transaction posts balanced double-entry lines: total debits equal total credits
                            {currencies.size > 1 ? " within each currency" : ""}. The database refuses any that do not.
                        </p>
                        <table className="journal">
                            <thead>
                                <tr><th>Account</th><th className="num">Debit</th><th className="num">Credit</th></tr>
                            </thead>
                            <tbody>
                                {details.journal.map((l, i) => (
                                    <tr key={i}>
                                        <td>
                                            <span className="code">{l.code}</span> {l.name}
                                            <small>{l.type.toLowerCase()}</small>
                                        </td>
                                        <td className="num">{Number(l.debit) > 0 ? money(Number(l.debit), l.currency) : ""}</td>
                                        <td className="num">{Number(l.credit) > 0 ? money(Number(l.credit), l.currency) : ""}</td>
                                    </tr>
                                ))}
                            </tbody>
                            {currencies.size === 1 && (
                                <tfoot>
                                    <tr>
                                        <td>Total</td>
                                        <td className="num">{money(debits, [...currencies][0])}</td>
                                        <td className="num">{money(credits, [...currencies][0])}</td>
                                    </tr>
                                </tfoot>
                            )}
                        </table>

                        <h3><ShieldCheck size={16} /> Tamper evidence</h3>
                        {details.seal ? (
                            <div className="seal-box ok">
                                <Lock size={16} />
                                <div>
                                    <strong>Sealed in seal #{details.seal.seq}</strong>
                                    <span>
                                        {when(details.seal.sealedAt)} ·{" "}
                                        {details.seal.signed
                                            ? `signed with Ed25519 key ${details.seal.keyId}`
                                            : "hash-chained (no signing key configured)"}
                                    </span>
                                    <small>
                                        Any later change to this transaction or its entries would break the chain of
                                        seals{details.seal.signed ? ", and no one without the bank's key could re-sign it" : ""}.
                                    </small>
                                </div>
                            </div>
                        ) : (
                            <div className="seal-box pending">
                                <Lock size={16} />
                                <div>
                                    <strong>Not sealed yet</strong>
                                    <span>Committed transactions are sealed into the hash chain within a few seconds.</span>
                                </div>
                            </div>
                        )}
                    </>
                )}
            </aside>
        </div>
    );
}

/**
 * The payee of a transfer can send all or part of it back. The refund is a new
 * reversal transaction; the original is never edited.
 */
function Refund({ token, transactionId, refundable, currency, onRefunded }: {
    token: string;
    transactionId: string;
    refundable: number;
    currency: string;
    onRefunded: () => void;
}) {
    const toast = useToast();
    const [open, setOpen] = useState(false);
    const [amount, setAmount] = useState("");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    // The same refund retried after an error keeps its key, so it cannot be paid twice.
    const pending = useRef<{ amount: string; key: string } | null>(null);

    if (refundable <= 0)
        return <p className="refund-note"><RotateCcw size={14} /> Fully refunded.</p>;

    async function submit(e: React.FormEvent) {
        e.preventDefault();
        const value = parseAmount(amount);
        if (value === null) return setError("Enter a positive amount with up to two decimal places.");
        if (value > refundable) return setError(`You can refund at most ${money(refundable, currency)}.`);
        const key = pending.current?.amount === amount ? pending.current.key : crypto.randomUUID();
        pending.current = { amount, key };
        setBusy(true);
        setError("");
        try {
            await refund(token, transactionId, value, key);
            pending.current = null;
            toast.success(`Refunded ${money(value, currency)}.`);
            setOpen(false);
            onRefunded();
        } catch (err) {
            setError((err as Error).message);
        } finally {
            setBusy(false);
        }
    }

    if (!open)
        return (
            <div className="refund-row">
                <button type="button" className="outline" onClick={() => { setAmount(refundable.toFixed(2)); setOpen(true); }}>
                    <RotateCcw size={15} /> Refund
                </button>
                <small>Up to {money(refundable, currency)} can be sent back.</small>
            </div>
        );

    return (
        <form className="refund-form" onSubmit={submit}>
            <label>
                Refund amount ({currency})
                <input type="number" min="0.01" step="0.01" required value={amount} disabled={busy}
                    onChange={(e) => setAmount(e.target.value)} />
            </label>
            <p className="drawer-note">
                The money goes back to the sender as a new reversal transaction. The original transfer stays on the
                ledger unchanged.
            </p>
            {error && <div className="error" role="alert">{error}</div>}
            <div className="transfer-actions">
                <button type="button" className="outline" disabled={busy} onClick={() => setOpen(false)}>Cancel</button>
                <button type="submit" className="primary" disabled={busy}>{busy ? "Refunding…" : "Send refund"}</button>
            </div>
        </form>
    );
}
