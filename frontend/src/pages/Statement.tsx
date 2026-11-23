import { useEffect, useState } from "react";
import { FileText, Printer } from "lucide-react";
import { statement, type Account, type Statement as StatementData } from "../api";
import { formatDate, money, signedMoney, typeLabel } from "../format";
import AccountSelect from "../components/AccountSelect";
import { Empty, Skeleton } from "../components/Skeleton";

export type Period = "30d" | "this-month" | "last-month" | "90d" | "year";

export const PERIODS: { value: Period; label: string }[] = [
    { value: "30d", label: "Last 30 days" },
    { value: "this-month", label: "This month" },
    { value: "last-month", label: "Last month" },
    { value: "90d", label: "Last 90 days" },
    { value: "year", label: "Last 12 months" },
];

/** The [from, to) range a period covers, ending at `now`; calendar months use local time. */
export function periodRange(period: Period, now = new Date()): { from: Date; to: Date } {
    const days = (n: number) => new Date(now.getTime() - n * 86_400_000);
    switch (period) {
        case "this-month":
            return { from: new Date(now.getFullYear(), now.getMonth(), 1), to: now };
        case "last-month":
            return {
                from: new Date(now.getFullYear(), now.getMonth() - 1, 1),
                to: new Date(now.getFullYear(), now.getMonth(), 1),
            };
        case "90d":
            return { from: days(90), to: now };
        case "year":
            return { from: days(365), to: now };
        default:
            return { from: days(30), to: now };
    }
}

type Props = {
    token: string;
    accounts: Account[];
    selected: string;
    onSelect: (id: string) => void;
    refreshKey: number;
    onOpenTransaction: (id: string) => void;
};

/**
 * A bank statement from the ledger: the balance at the start of the period, each
 * posting with the running balance after it, and the balance at the end. The server
 * refuses to return one that does not add up.
 */
export default function Statement({ token, accounts, selected, onSelect, refreshKey, onOpenTransaction }: Props) {
    const [period, setPeriod] = useState<Period>("30d");
    const [data, setData] = useState<StatementData | null>(null);
    const [error, setError] = useState("");

    useEffect(() => {
        if (!selected) return;
        let cancelled = false;
        const { from, to } = periodRange(period);
        setData(null);
        setError("");
        statement(token, selected, from, to)
            .then((s) => !cancelled && setData(s))
            .catch((e) => !cancelled && setError((e as Error).message));
        return () => {
            cancelled = true;
        };
    }, [token, selected, period, refreshKey]);

    const c = data?.currency ?? "CAD";
    return (
        <section className="panel history-page statement">
            <div className="history-toolbar no-print">
                <AccountSelect accounts={accounts} value={selected} onChange={onSelect} />
                <label>
                    Period
                    <select value={period} onChange={(e) => setPeriod(e.target.value as Period)}>
                        {PERIODS.map((p) => <option key={p.value} value={p.value}>{p.label}</option>)}
                    </select>
                </label>
                <button type="button" className="outline toolbar-button" disabled={!data} onClick={() => window.print()}>
                    <Printer size={16} /> Print
                </button>
            </div>
            {error && <div className="error" role="alert">{error}</div>}
            {!data && !error && <Skeleton lines={7} />}
            {data && (
                <>
                    <div className="statement-head">
                        <h2><FileText size={18} /> Statement for {data.accountNumber}</h2>
                        <span>{formatDate(data.from)} to {formatDate(data.to)} · {data.currency}</span>
                    </div>
                    <dl className="balance-strip">
                        <div><dt>Opening balance</dt><dd>{money(Number(data.openingBalance), c)}</dd></div>
                        <div><dt>Money in</dt><dd className="amount-in">{signedMoney(Number(data.moneyIn), c)}</dd></div>
                        <div><dt>Money out</dt><dd>{signedMoney(-Number(data.moneyOut), c)}</dd></div>
                        <div><dt>Closing balance</dt><dd className="strong">{money(Number(data.closingBalance), c)}</dd></div>
                    </dl>
                    {data.lines.length === 0 ? (
                        <Empty icon={<FileText size={24} />} title="No activity in this period">
                            The balance stayed at {money(Number(data.closingBalance), c)}.
                        </Empty>
                    ) : (
                        <div className="history-scroll">
                            <table className="history-table statement-table">
                                <thead>
                                    <tr>
                                        <th>Date</th>
                                        <th>Description</th>
                                        <th className="amount-cell">Amount</th>
                                        <th className="amount-cell">Balance</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    <tr className="statement-edge">
                                        <td className="date-cell">{formatDate(data.from)}</td>
                                        <td className="type-cell">Opening balance</td>
                                        <td className="amount-cell" />
                                        <td className="amount-cell balance-cell">{money(Number(data.openingBalance), c)}</td>
                                    </tr>
                                    {data.lines.map((l, i) => {
                                        const amount = Number(l.amount);
                                        const open = () => onOpenTransaction(l.transactionId);
                                        return (
                                            <tr key={`${l.transactionId}-${i}`} className="clickable" tabIndex={0} onClick={open}
                                                onKeyDown={(e) => e.key === "Enter" && open()}>
                                                <td className="date-cell">{formatDate(l.postedAt)}</td>
                                                <td className="type-cell">
                                                    {typeLabel(l.type)}
                                                    {l.counterparty && <small> · {amount >= 0 ? "from" : "to"} {l.counterparty}</small>}
                                                </td>
                                                <td className={`amount-cell ${amount >= 0 ? "amount-in" : "amount-out"}`}>
                                                    {signedMoney(amount, c)}
                                                </td>
                                                <td className="amount-cell balance-cell">{money(Number(l.balance), c)}</td>
                                            </tr>
                                        );
                                    })}
                                    <tr className="statement-edge">
                                        <td className="date-cell">{formatDate(data.to)}</td>
                                        <td className="type-cell">Closing balance</td>
                                        <td className="amount-cell" />
                                        <td className="amount-cell balance-cell">{money(Number(data.closingBalance), c)}</td>
                                    </tr>
                                </tbody>
                            </table>
                        </div>
                    )}
                    {data.truncated && (
                        <p className="fine">This period has more postings than one statement shows. Choose a shorter period.</p>
                    )}
                </>
            )}
        </section>
    );
}
