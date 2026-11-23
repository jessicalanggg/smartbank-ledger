import { useRef, useState } from "react";
import { ArrowDownLeft, ArrowUpRight } from "lucide-react";
import { deposit, withdraw, type Account } from "../api";
import { money, parseAmount } from "../format";
import { useToast } from "./Toasts";

type Action = "deposit" | "withdraw";

/** Deposit into or withdraw from the selected account. */
export default function MoveMoney({ token, account, onDone }: { token: string; account: Account; onDone: () => void }) {
    const toast = useToast();
    const [action, setAction] = useState<Action>("deposit");
    const [amount, setAmount] = useState("");
    const [description, setDescription] = useState("");
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState("");
    // Retrying the exact same failed operation reuses its key, so a request that reached
    // the server before the error is not posted twice.
    const pending = useRef<{ action: Action; accountId: string; amount: string; requestKey: string } | null>(null);

    async function submit(e: React.FormEvent) {
        e.preventDefault();
        const value = parseAmount(amount);
        if (value === null) {
            setError("Enter a positive amount with up to two decimal places.");
            return;
        }
        const last = pending.current;
        const requestKey =
            last && last.action === action && last.accountId === account.id && last.amount === amount
                ? last.requestKey
                : crypto.randomUUID();
        pending.current = { action, accountId: account.id, amount, requestKey };
        setBusy(true);
        setError("");
        try {
            if (action === "deposit") await deposit(token, account.id, value, description, requestKey);
            else await withdraw(token, account.id, value, description, requestKey);
            pending.current = null;
            toast.success(`${action === "deposit" ? "Deposited" : "Withdrew"} ${money(value, account.currency)}.`);
            setAmount("");
            setDescription("");
            onDone();
        } catch (err) {
            setError((err as Error).message);
        } finally {
            setBusy(false);
        }
    }

    return (
        <section className="panel">
            <h2>Move money</h2>
            <p>Deposit or withdraw from {account.accountNumber}.</p>
            <div className="tabs" role="tablist">
                <button type="button" role="tab" aria-selected={action === "deposit"}
                    className={action === "deposit" ? "chosen" : ""} onClick={() => setAction("deposit")}>
                    <ArrowDownLeft size={16} /> Deposit
                </button>
                <button type="button" role="tab" aria-selected={action === "withdraw"}
                    className={action === "withdraw" ? "chosen" : ""} onClick={() => setAction("withdraw")}>
                    <ArrowUpRight size={16} /> Withdraw
                </button>
            </div>
            <form onSubmit={submit}>
                <label>
                    Amount ({account.currency})
                    <input type="number" min="0.01" step="0.01" required value={amount}
                        onChange={(e) => setAmount(e.target.value)} placeholder="0.00" />
                </label>
                <label>
                    Description
                    <input value={description} onChange={(e) => setDescription(e.target.value)} placeholder="Optional note" />
                </label>
                {error && <div className="error" role="alert">{error}</div>}
                <button disabled={busy}>
                    {busy ? "Processing…" : action === "deposit" ? "Make deposit" : "Make withdrawal"}
                </button>
            </form>
        </section>
    );
}
