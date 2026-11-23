import { Inbox, Wallet } from "lucide-react";
import type { Account, Transaction } from "../api";
import { describe, formatDate, money, signedMoney, totalsByCurrency } from "../format";
import MoveMoney from "../components/MoveMoney";
import TransferForm from "../components/TransferForm";
import { AccountCardSkeleton, Empty, Skeleton } from "../components/Skeleton";

type Props = {
    token: string;
    accounts: Account[];
    /** Null until the first load finishes. */
    loaded: boolean;
    selected: string;
    onSelect: (id: string) => void;
    history: Transaction[] | null;
    onChanged: () => void;
    onOpenTransaction: (id: string) => void;
};

/** Balances per currency, the accounts, and the everyday actions on the selected one. */
export default function Overview({ token, accounts, loaded, selected, onSelect, history, onChanged, onOpenTransaction }: Props) {
    const active = accounts.find((a) => a.id === selected);
    const totals = totalsByCurrency(accounts);
    const [main, ...rest] = totals;
    return (
        <>
            <div className="summary">
                <div className="eyebrow">TOTAL AVAILABLE BALANCE</div>
                {!loaded ? (
                    <div className="big-balance skeleton-light">&nbsp;</div>
                ) : (
                    <>
                        <div className="big-balance">
                            {main ? money(main.total, main.currency) : money(0, "CAD")}
                            {main && <span className="big-currency"> {main.currency}</span>}
                        </div>
                        {rest.map((t) => (
                            <div className="other-balance" key={t.currency}>
                                + {money(t.total, t.currency)} {t.currency}
                            </div>
                        ))}
                        <div className="muted">
                            Across {accounts.length} account{accounts.length === 1 ? "" : "s"}
                            {totals.length > 1 ? ` in ${totals.length} currencies, shown separately` : ""}
                        </div>
                    </>
                )}
            </div>
            <h2>My accounts</h2>
            <div className="account-grid">
                {!loaded && [0, 1].map((i) => <AccountCardSkeleton key={i} />)}
                {accounts.map((a) => (
                    <button key={a.id} type="button" onClick={() => onSelect(a.id)} aria-pressed={selected === a.id}
                        className={`account-card ${selected === a.id ? "selected" : ""}`}>
                        <div className="account-top">
                            <span>{a.accountType}</span>
                            <Wallet size={21} />
                        </div>
                        <strong>{money(Number(a.availableBalance ?? a.balance), a.currency)}</strong>
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
            </div>
            {loaded && accounts.length === 0 && (
                <Empty icon={<Wallet size={26} />} title="No accounts yet">
                    Accounts opened for you will appear here.
                </Empty>
            )}
            {active && (
                <>
                    <div className="lower">
                        <MoveMoney token={token} account={active} onDone={onChanged} />
                        <section className="panel">
                            <h2>Recent transactions</h2>
                            <p>Activity for {active.accountNumber}. Select one for its ledger entries.</p>
                            <div className="tx-list">
                                {history === null ? (
                                    <Skeleton lines={5} />
                                ) : history.length === 0 ? (
                                    <Empty icon={<Inbox size={24} />} title="No transactions yet">
                                        Deposits, transfers and payments will show up here.
                                    </Empty>
                                ) : (
                                    history.slice(0, 8).map((t, i) => {
                                        const line = describe(t, active.id, accounts);
                                        const open = () => onOpenTransaction(String(t.id));
                                        return (
                                            <div className="tx clickable" key={String(t.id ?? i)} role="button" tabIndex={0}
                                                onClick={open} onKeyDown={(e) => e.key === "Enter" && open()}>
                                                <span className="tx-text">
                                                    <span className="tx-label">{line.label}</span>
                                                    <small>{[line.detail, formatDate(t.createdAt)].filter(Boolean).join(" · ")}</small>
                                                </span>
                                                <strong className={line.amount >= 0 ? "amount-in" : "amount-out"}>
                                                    {signedMoney(line.amount, line.currency)}
                                                </strong>
                                            </div>
                                        );
                                    })
                                )}
                            </div>
                        </section>
                    </div>
                    <TransferForm token={token} account={active} onDone={onChanged} />
                </>
            )}
        </>
    );
}
