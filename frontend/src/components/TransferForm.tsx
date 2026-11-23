import { useRef, useState } from "react";
import { Send } from "lucide-react";
import { transferByAccountNumber, type Account, type TransferReceipt } from "../api";
import { formatDate, money, parseAmount } from "../format";
import { useToast } from "./Toasts";

type Draft = { fromAccountId: string; fromAccountNumber: string; toAccountNumber: string; amount: number; currency: string; requestKey: string };

/**
 * Send money by account number in three steps: fill in, review, confirm. A failed
 * confirmation keeps the same request key, so retrying can never pay twice.
 */
export default function TransferForm({ token, account, onDone }: { token: string; account: Account; onDone: () => void }) {
    const toast = useToast();
    const [destination, setDestination] = useState("");
    const [amount, setAmount] = useState("");
    const [draft, setDraft] = useState<Draft | null>(null);
    const [receipt, setReceipt] = useState<{ transaction: TransferReceipt; from: string; to: string } | null>(null);
    const [uncertain, setUncertain] = useState(false);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    const inFlight = useRef(false);

    function review(e: React.FormEvent) {
        e.preventDefault();
        const value = parseAmount(amount);
        const target = destination.trim();
        if (value === null) return setError("Enter a positive amount with up to two decimal places.");
        if (!target) return setError("Enter a destination account number.");
        if (account.accountNumber.toUpperCase() === target.toUpperCase())
            return setError("Source and destination accounts must differ.");
        if (value > Number(account.availableBalance ?? account.balance))
            return setError("The amount exceeds your available balance.");
        setError("");
        setReceipt(null);
        setUncertain(false);
        setDraft({
            fromAccountId: account.id,
            fromAccountNumber: account.accountNumber,
            toAccountNumber: target,
            amount: value,
            currency: account.currency,
            requestKey: crypto.randomUUID(),
        });
    }

    async function confirm() {
        if (!draft || inFlight.current) return;
        inFlight.current = true;
        setBusy(true);
        setError("");
        try {
            const r = await transferByAccountNumber(token, draft.fromAccountId, draft.toAccountNumber, draft.amount, draft.requestKey);
            setReceipt({ transaction: r, from: draft.fromAccountNumber, to: draft.toAccountNumber });
            toast.success(`Sent ${money(draft.amount, draft.currency)} to ${draft.toAccountNumber}.`);
            setDraft(null);
            setUncertain(false);
            setDestination("");
            setAmount("");
            onDone();
        } catch (err) {
            setUncertain(true);
            setError(`${(err as Error).message}. If the request may have reached the server, check your transactions first; retrying here uses the same request key, so it cannot pay twice.`);
        } finally {
            inFlight.current = false;
            setBusy(false);
        }
    }

    return (
        <>
            <section className="panel" style={{ marginTop: 20 }}>
                <h2><Send size={18} /> Transfer money</h2>
                <p>Send money from {account.accountNumber} to another demo account.</p>
                <form onSubmit={review}>
                    <label>
                        From account
                        <input value={`${account.accountNumber} (${account.currency})`} disabled />
                    </label>
                    <label>
                        Destination account number
                        <input required value={destination} disabled={!!draft || busy}
                            onChange={(e) => setDestination(e.target.value)} placeholder="e.g. DEMO-10002" />
                    </label>
                    <label>
                        Amount ({account.currency})
                        <input type="number" min="0.01" step="0.01" required value={amount} disabled={!!draft || busy}
                            onChange={(e) => setAmount(e.target.value)} placeholder="0.00" />
                    </label>
                    {error && !draft && <div className="error" role="alert">{error}</div>}
                    <button type="submit" disabled={busy || !!draft}>Review transfer</button>
                </form>
            </section>
            {draft && (
                <section className="panel" style={{ marginTop: 20 }} aria-label="Transfer confirmation">
                    <h2>Confirm your transfer</h2>
                    <p>Review these details before sending. No money has moved yet.</p>
                    <dl className="transfer-details">
                        <dt>From</dt><dd>{draft.fromAccountNumber}</dd>
                        <dt>To</dt><dd>{draft.toAccountNumber}</dd>
                        <dt>Amount</dt><dd><strong>{money(draft.amount, draft.currency)}</strong></dd>
                    </dl>
                    {error && <div className="error" role="alert">{error}</div>}
                    <div className="transfer-actions">
                        {!uncertain && (
                            <button type="button" className="outline" disabled={busy} onClick={() => setDraft(null)}>
                                Edit / Cancel
                            </button>
                        )}
                        <button type="button" className="primary" disabled={busy} onClick={() => void confirm()}>
                            {busy ? "Processing…" : uncertain ? "Retry same transfer" : "Confirm transfer"}
                        </button>
                    </div>
                </section>
            )}
            {receipt && (
                <section className="panel transfer-receipt" style={{ marginTop: 20 }} aria-label="Transfer receipt" role="status">
                    <h2>✓ Transfer completed</h2>
                    <p>SmartBank demo transaction receipt</p>
                    <h2>{money(Number(receipt.transaction.amount), receipt.transaction.currency)}</h2>
                    <dl className="transfer-details">
                        <dt>Status</dt><dd>Completed</dd>
                        <dt>From</dt><dd>{receipt.from}</dd>
                        <dt>To</dt><dd>{receipt.to}</dd>
                        <dt>Date</dt><dd>{formatDate(receipt.transaction.createdAt)}</dd>
                        <dt>Transaction ID</dt><dd className="mono">{receipt.transaction.id}</dd>
                    </dl>
                    <div className="transfer-actions">
                        <button type="button" className="outline" onClick={() => window.print()}>Print receipt</button>
                        <button type="button" className="outline" onClick={() => setReceipt(null)}>Done</button>
                    </div>
                </section>
            )}
        </>
    );
}
