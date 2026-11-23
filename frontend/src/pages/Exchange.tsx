import { useEffect, useRef, useState } from "react";
import { ArrowLeftRight, ArrowRight, Clock, Coins } from "lucide-react";
import { fxExecute, fxQuote, fxRates, type Account, type FxQuote, type FxRate } from "../api";
import { money, parseAmount } from "../format";
import AccountSelect from "../components/AccountSelect";
import { Empty, Skeleton } from "../components/Skeleton";
import { useToast } from "../components/Toasts";

/** Whole seconds left until `expiresAt`, never below zero. */
export function secondsLeft(expiresAt: string, now = Date.now()): number {
    return Math.max(0, Math.ceil((new Date(expiresAt).getTime() - now) / 1000));
}

/** The time left on a quote, ticking every quarter second. */
function useCountdown(expiresAt: string | undefined): number {
    const [now, setNow] = useState(Date.now());
    useEffect(() => {
        if (!expiresAt) return;
        setNow(Date.now());
        const timer = setInterval(() => setNow(Date.now()), 250);
        return () => clearInterval(timer);
    }, [expiresAt]);
    return expiresAt ? secondsLeft(expiresAt, now) : 0;
}

type Props = { token: string; accounts: Account[]; onChanged: () => void };

/**
 * Currency exchange between the user's own accounts. A quote fixes the rate and both
 * amounts for 60 seconds; confirming it moves the money at exactly that rate.
 */
export default function Exchange({ token, accounts, onChanged }: Props) {
    const toast = useToast();
    const [rates, setRates] = useState<FxRate[] | null>(null);
    const [fromId, setFromId] = useState(accounts[0]?.id ?? "");
    const [toId, setToId] = useState("");
    const [amount, setAmount] = useState("");
    const [quote, setQuote] = useState<FxQuote | null>(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const requestKey = useRef("");

    const from = accounts.find((a) => a.id === fromId);
    const targets = accounts.filter((a) => from && a.currency !== from.currency);
    const to = targets.find((a) => a.id === toId) ?? targets[0];
    const left = useCountdown(quote?.expiresAt);
    const expired = quote !== null && left === 0;

    useEffect(() => {
        fxRates(token).then(setRates).catch(() => setRates([]));
    }, [token]);

    useEffect(() => {
        if (!fromId && accounts[0]) setFromId(accounts[0].id);
    }, [accounts, fromId]);

    async function getQuote(e?: React.FormEvent) {
        e?.preventDefault();
        if (!from || !to) return;
        const value = parseAmount(amount);
        if (value === null) return setError("Enter a positive amount with up to two decimal places.");
        if (value > Number(from.availableBalance ?? from.balance)) return setError("The amount exceeds your available balance.");
        setBusy(true);
        setError("");
        try {
            setQuote(await fxQuote(token, from.id, to.id, value));
            requestKey.current = crypto.randomUUID();
        } catch (err) {
            setError((err as Error).message);
        } finally {
            setBusy(false);
        }
    }

    async function confirm() {
        if (!quote || expired) return;
        setBusy(true);
        setError("");
        try {
            await fxExecute(token, quote.id, requestKey.current);
            toast.success(`Converted ${money(Number(quote.sellAmount), quote.sellCurrency)} into ${money(Number(quote.buyAmount), quote.buyCurrency)}.`);
            setQuote(null);
            setAmount("");
            onChanged();
        } catch (err) {
            setError((err as Error).message);
        } finally {
            setBusy(false);
        }
    }

    if (accounts.length > 0 && new Set(accounts.map((a) => a.currency)).size < 2)
        return (
            <section className="panel holds-page">
                <Empty icon={<Coins size={26} />} title="You need accounts in two currencies">
                    Exchange moves money between your own accounts in different currencies.
                </Empty>
            </section>
        );

    return (
        <div className="holds-page">
            <div className="lower">
                <section className="panel">
                    <h2><ArrowLeftRight size={18} /> Convert money</h2>
                    <p>Between your own accounts. The rate is held for 60 seconds once quoted.</p>
                    <form onSubmit={getQuote}>
                        <AccountSelect label="From" accounts={accounts} value={fromId}
                            onChange={(id) => { setFromId(id); setQuote(null); }} />
                        {targets.length > 0 && to && (
                            <AccountSelect label="To" accounts={targets} value={to.id}
                                onChange={(id) => { setToId(id); setQuote(null); }} />
                        )}
                        <label>
                            Amount to convert ({from?.currency ?? ""})
                            <input type="number" min="0.01" step="0.01" required value={amount} disabled={busy}
                                onChange={(e) => { setAmount(e.target.value); setQuote(null); }} placeholder="0.00" />
                        </label>
                        {from && <p className="fine">Available: {money(Number(from.availableBalance ?? from.balance), from.currency)}</p>}
                        {error && !quote && <div className="error" role="alert">{error}</div>}
                        {!quote && <button disabled={busy || !to}>{busy ? "Getting a quote…" : "Get quote"}</button>}
                    </form>
                    {quote && (
                        <div className={`quote ${expired ? "expired" : ""}`} aria-live="polite">
                            <div className="quote-amounts">
                                <div>
                                    <small>You convert</small>
                                    <strong>{money(Number(quote.sellAmount), quote.sellCurrency)}</strong>
                                </div>
                                <ArrowRight size={20} />
                                <div>
                                    <small>You receive</small>
                                    <strong>{money(Number(quote.buyAmount), quote.buyCurrency)}</strong>
                                </div>
                            </div>
                            <div className="quote-rate">
                                1 {quote.sellCurrency} = {Number(quote.rate)} {quote.buyCurrency}
                            </div>
                            <div className="quote-timer">
                                <Clock size={15} />
                                {expired ? "This quote has expired. Get a new one for the current rate." : `Rate held for ${left}s`}
                                <span className="timer-bar" style={{ width: `${(left / 60) * 100}%` }} />
                            </div>
                            {error && <div className="error" role="alert">{error}</div>}
                            <div className="transfer-actions">
                                <button type="button" className="outline" disabled={busy} onClick={() => setQuote(null)}>Cancel</button>
                                {expired ? (
                                    <button type="button" className="primary" disabled={busy} onClick={() => void getQuote()}>
                                        Get a new quote
                                    </button>
                                ) : (
                                    <button type="button" className="primary" disabled={busy} onClick={() => void confirm()}>
                                        {busy ? "Converting…" : "Confirm exchange"}
                                    </button>
                                )}
                            </div>
                        </div>
                    )}
                </section>
                <section className="panel">
                    <h2><Coins size={18} /> Today's rates</h2>
                    <p>Set by the bank. Every conversion records the rate it used.</p>
                    {rates === null ? (
                        <Skeleton lines={5} />
                    ) : rates.length === 0 ? (
                        <Empty title="No rates published" />
                    ) : (
                        <table className="journal rates">
                            <thead>
                                <tr><th>Pair</th><th className="num">Rate</th></tr>
                            </thead>
                            <tbody>
                                {rates.map((r) => (
                                    <tr key={r.id}>
                                        <td>1 {r.baseCurrency} → {r.quoteCurrency}</td>
                                        <td className="num">{Number(r.rate)}</td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    )}
                </section>
            </div>
        </div>
    );
}
