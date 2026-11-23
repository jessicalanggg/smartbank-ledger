import { useEffect, useState } from "react";
import { BookOpen, Lock, ShieldCheck, X } from "lucide-react";
import { transactionDetails, type Account, type TransactionDetails } from "./api";
import { describe, money, signedMoney } from "./format";

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
};

/**
 * One transaction as the bank's books record it: the double-entry lines it posted on
 * the chart of accounts, and the seal in the tamper-evident hash chain that covers it.
 */
export default function TransactionDetail({ token, transactionId, accountId, accounts, onClose }: Props) {
    const [details, setDetails] = useState<TransactionDetails | null>(null);
    const [error, setError] = useState("");

    useEffect(() => {
        let cancelled = false;
        transactionDetails(token, transactionId)
            .then((d) => !cancelled && setDetails(d))
            .catch((e) => !cancelled && setError((e as Error).message));
        return () => {
            cancelled = true;
        };
    }, [token, transactionId]);

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
