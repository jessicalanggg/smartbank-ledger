import { useCallback, useEffect, useState } from "react";
import { BookOpen, CheckCircle2, KeyRound, Link2, ShieldCheck, XCircle } from "lucide-react";
import {
    sealHead,
    sealPublicKeys,
    trialBalance,
    verifySeals,
    type PublishedKey,
    type Seal,
    type SealVerification,
    type TrialBalance,
} from "./api";
import { money } from "./format";

const CURRENCIES = ["CAD", "USD", "EUR", "GBP", "JPY"];

const when = (value?: string) =>
    value
        ? new Intl.DateTimeFormat("en-CA", { timeZone: "America/Toronto", dateStyle: "medium", timeStyle: "medium" })
              .format(new Date(value))
        : "—";

const short = (hash?: string | null) => (hash ? `${hash.slice(0, 10)}…${hash.slice(-6)}` : "—");

function Check({ ok, children }: { ok: boolean; children: React.ReactNode }) {
    return (
        <div className={`check ${ok ? "ok" : "bad"}`}>
            {ok ? <CheckCircle2 size={18} /> : <XCircle size={18} />} {children}
        </div>
    );
}

/**
 * The bank's books, for administrators: each currency's trial balance on the chart of
 * accounts, and the tamper-evident chain of signed seals over every transaction.
 */
export default function Ledger({ token }: { token: string }) {
    const [currency, setCurrency] = useState("CAD");
    const [books, setBooks] = useState<TrialBalance | null>(null);
    const [head, setHead] = useState<Seal | null>(null);
    const [keys, setKeys] = useState<PublishedKey[]>([]);
    const [verification, setVerification] = useState<SealVerification | null>(null);
    const [verifying, setVerifying] = useState(false);
    const [verifiedAt, setVerifiedAt] = useState<Date | null>(null);
    const [error, setError] = useState("");

    const load = useCallback(async () => {
        setError("");
        try {
            const [b, h, k] = await Promise.all([trialBalance(token, currency), sealHead(token), sealPublicKeys(token)]);
            setBooks(b);
            setHead(h);
            setKeys(k);
        } catch (e) {
            setError((e as Error).message);
        }
    }, [token, currency]);

    useEffect(() => {
        void load();
    }, [load]);

    async function verify() {
        setVerifying(true);
        setError("");
        try {
            setVerification(await verifySeals(token));
            setVerifiedAt(new Date());
            setHead(await sealHead(token));
        } catch (e) {
            setError((e as Error).message);
        } finally {
            setVerifying(false);
        }
    }

    // System accounts first, then customer accounts under the 2000 control account.
    const rows = books
        ? [...books.accounts].sort((a, b) => a.code.localeCompare(b.code, undefined, { numeric: true }))
        : [];
    const intact = verification && verification.firstBrokenSeal === null;

    return (
        <div className="holds-page">
            {error && <div className="error" role="alert">{error}</div>}

            <section className="panel">
                <div className="ledger-head">
                    <h2><BookOpen size={18} /> Trial balance</h2>
                    <label className="inline-select">
                        Currency
                        <select value={currency} onChange={(e) => setCurrency(e.target.value)}>
                            {CURRENCIES.map((c) => <option key={c}>{c}</option>)}
                        </select>
                    </label>
                </div>
                <p>
                    Every account on the chart of accounts, read from the double-entry ledger. Customer accounts are
                    liabilities: money the bank owes its customers.
                </p>
                {books && (
                    <>
                        <div className="checks">
                            <Check ok={books.balanced}>
                                Debits {money(Number(books.totalDebits), currency)} = credits {money(Number(books.totalCredits), currency)}
                            </Check>
                            <Check ok={books.accountingEquationHolds}>
                                Assets = Liabilities + Equity + Revenue − Expenses
                            </Check>
                        </div>
                        <div className="history-scroll">
                            <table className="history-table ledger-table">
                                <thead>
                                    <tr>
                                        <th>Account</th>
                                        <th>Type</th>
                                        <th className="num">Debits</th>
                                        <th className="num">Credits</th>
                                        <th className="num">Balance</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {rows.map((a) => (
                                        <tr key={a.id} className={a.parentId ? "child" : ""}>
                                            <td><span className="code">{a.code}</span> {a.name}</td>
                                            <td>{a.type.toLowerCase()}</td>
                                            <td className="num">{money(Number(a.debits), currency)}</td>
                                            <td className="num">{money(Number(a.credits), currency)}</td>
                                            <td className="num strong">{money(Number(a.balance), currency)}</td>
                                        </tr>
                                    ))}
                                </tbody>
                                <tfoot>
                                    <tr>
                                        <td colSpan={2}>Total</td>
                                        <td className="num">{money(Number(books.totalDebits), currency)}</td>
                                        <td className="num">{money(Number(books.totalCredits), currency)}</td>
                                        <td />
                                    </tr>
                                </tfoot>
                            </table>
                        </div>
                    </>
                )}
            </section>

            <section className="panel" style={{ marginTop: 22 }}>
                <h2><ShieldCheck size={18} /> Tamper evidence</h2>
                <p>
                    Committed transactions are sealed into a SHA-256 hash chain, each seal signed with an Ed25519 key
                    that is kept out of the database. Verifying recomputes every seal from the ledger as it is now:
                    any changed, added or removed transaction, or a rewritten chain, is reported.
                </p>
                <dl className="balance-strip">
                    <div>
                        <dt><Link2 size={13} /> Latest seal</dt>
                        <dd>{head ? `#${head.seq}` : "None yet"}</dd>
                        <small className="mono">{short(head?.hash)}</small>
                    </div>
                    <div>
                        <dt>Sealed</dt>
                        <dd className="small-dd">{when(head?.sealedAt)}</dd>
                        <small>{head ? `${head.transactionCount} transaction${head.transactionCount === 1 ? "" : "s"}` : ""}</small>
                    </div>
                    <div>
                        <dt><KeyRound size={13} /> Signing key</dt>
                        <dd className="small-dd">{keys.find((k) => k.current)?.keyId ?? "Not configured"}</dd>
                        <small>{keys.length ? `${keys[0].algorithm} · ${keys.length} key${keys.length === 1 ? "" : "s"} published` : ""}</small>
                    </div>
                </dl>
                <div className="verify-row">
                    <button type="button" className="primary-small" disabled={verifying} onClick={() => void verify()}>
                        <ShieldCheck size={15} /> {verifying ? "Verifying…" : "Verify history"}
                    </button>
                    {verification && (
                        intact ? (
                            <Check ok>
                                {verification.sealsChecked} seals and {verification.transactionsChecked} transactions
                                rechecked; the chain is intact{head?.signature ? " and every signature is valid" : ""}
                                {verifiedAt ? ` (${verifiedAt.toLocaleTimeString()})` : ""}.
                            </Check>
                        ) : (
                            <Check ok={false}>
                                Seal #{verification.firstBrokenSeal} is broken: {verification.problem}.
                            </Check>
                        )
                    )}
                </div>
            </section>
        </div>
    );
}
