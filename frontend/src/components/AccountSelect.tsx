import type { Account } from "../api";

/** A labelled dropdown of the user's accounts. */
export default function AccountSelect({ label = "Account", accounts, value, onChange, filter }: {
    label?: string;
    accounts: Account[];
    value: string;
    onChange: (id: string) => void;
    filter?: (a: Account) => boolean;
}) {
    return (
        <label>
            {label}
            <select value={value} onChange={(e) => onChange(e.target.value)}>
                {accounts.filter(filter ?? (() => true)).map((a) => (
                    <option key={a.id} value={a.id}>
                        {a.accountNumber} · {a.accountType} · {a.currency}
                    </option>
                ))}
            </select>
        </label>
    );
}
