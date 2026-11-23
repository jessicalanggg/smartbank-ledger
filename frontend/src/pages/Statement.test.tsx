import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import Statement, { periodRange } from "./Statement";
import { CAD, fakeApi } from "../test/fakeApi";

describe("Statement", () => {
    it("turns a period into a range", () => {
        const now = new Date(2026, 10, 22, 15, 0);
        expect(periodRange("this-month", now)).toEqual({ from: new Date(2026, 10, 1), to: now });
        expect(periodRange("last-month", now)).toEqual({ from: new Date(2026, 9, 1), to: new Date(2026, 10, 1) });
        expect(periodRange("30d", now).from).toEqual(new Date(now.getTime() - 30 * 86_400_000));
    });

    it("shows opening balance, each posting with its running balance, and closing balance", async () => {
        const { calls } = fakeApi({
            [`GET /accounts/${CAD.id}/statement`]: {
                accountId: CAD.id, accountNumber: "DEMO-10001", currency: "CAD",
                from: "2026-10-23T00:00:00Z", to: "2026-11-22T00:00:00Z",
                openingBalance: 1000, moneyIn: 300, moneyOut: 50, closingBalance: 1250, truncated: false,
                lines: [
                    { postedAt: "2026-11-01T14:00:00Z", transactionId: "a", type: "DEPOSIT", counterparty: null, amount: 300, balance: 1300 },
                    { postedAt: "2026-11-02T14:00:00Z", transactionId: "b", type: "TRANSFER", counterparty: "DEMO-10002", amount: -50, balance: 1250 },
                ],
            },
        });
        render(<Statement token="t" accounts={[CAD]} selected={CAD.id} onSelect={() => {}} refreshKey={0} onOpenTransaction={() => {}} />);

        const rows = within(await screen.findByRole("table")).getAllByRole("row").slice(1);
        expect(rows.map((r) => r.textContent)).toEqual([
            expect.stringMatching(/Opening balance\$1,000\.00$/),
            expect.stringMatching(/Deposit\+\$300\.00\$1,300\.00$/),
            expect.stringMatching(/Transfer · to DEMO-10002−\$50\.00\$1,250\.00$/),
            expect.stringMatching(/Closing balance\$1,250\.00$/),
        ]);
        const url = new URL(String(calls[0].path), "http://x");
        expect(url.pathname).toBe(`/accounts/${CAD.id}/statement`);
    });
});
