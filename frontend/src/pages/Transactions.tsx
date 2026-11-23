import { useState } from "react";
import { Inbox, SearchX } from "lucide-react";
import type { Account, Transaction } from "../api";
import { describe, formatDate, signedMoney, typeLabel } from "../format";
import AccountSelect from "../components/AccountSelect";
import { Empty, Skeleton } from "../components/Skeleton";

type Props = {
    accounts: Account[];
    selected: string;
    onSelect: (id: string) => void;
    history: Transaction[] | null;
    onOpenTransaction: (id: string) => void;
};

/** Every transaction on one account, filterable by type and searchable. */
export default function Transactions({ accounts, selected, onSelect, history, onOpenTransaction }: Props) {
    const [filter, setFilter] = useState("all");
    const [search, setSearch] = useState("");
    const types = Array.from(new Set((history ?? []).map((t) => String(t.type ?? "UNKNOWN"))));
    const rows = (history ?? [])
        .map((t) => ({ t, line: describe(t, selected, accounts) }))
        .filter(({ t, line }) =>
            (filter === "all" || String(t.type ?? "UNKNOWN") === filter) &&
            `${line.label} ${line.detail}`.toLowerCase().includes(search.trim().toLowerCase()));

    return (
        <section className="panel history-page">
            <div className="history-toolbar">
                <AccountSelect accounts={accounts} value={selected} onChange={onSelect} />
                <label>
                    Type
                    <select value={filter} onChange={(e) => setFilter(e.target.value)}>
                        <option value="all">All types</option>
                        {types.map((t) => <option key={t} value={t}>{typeLabel(t)}</option>)}
                    </select>
                </label>
                <label>
                    Search
                    <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Type or account number" />
                </label>
            </div>
            <div className="history-scroll">
                {history === null ? (
                    <Skeleton lines={6} />
                ) : history.length === 0 ? (
                    <Empty icon={<Inbox size={24} />} title="No transactions on this account" />
                ) : rows.length === 0 ? (
                    <Empty icon={<SearchX size={24} />} title="Nothing matches">
                        Try another type or search.
                    </Empty>
                ) : (
                    <table className="history-table">
                        <thead>
                            <tr>
                                <th>Date</th>
                                <th>Type</th>
                                <th>Details</th>
                                <th className="amount-cell">Amount</th>
                            </tr>
                        </thead>
                        <tbody>
                            {rows.map(({ t, line }, i) => {
                                const open = () => onOpenTransaction(String(t.id));
                                return (
                                    <tr key={String(t.id ?? i)} className="clickable" tabIndex={0} onClick={open}
                                        onKeyDown={(e) => e.key === "Enter" && open()}>
                                        <td className="date-cell">{formatDate(t.createdAt)}</td>
                                        <td className="type-cell">{line.label}</td>
                                        <td className="detail-cell">{line.detail || "—"}</td>
                                        <td className={`amount-cell ${line.amount >= 0 ? "amount-in" : "amount-out"}`}>
                                            {signedMoney(line.amount, line.currency)}
                                        </td>
                                    </tr>
                                );
                            })}
                        </tbody>
                    </table>
                )}
            </div>
        </section>
    );
}
