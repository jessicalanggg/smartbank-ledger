import { describe as suite, expect, it } from "vitest";
import { describe, formatDate, money, parseAmount, signedMoney, totalsByCurrency, typeLabel } from "./format";
import { CAD, USD } from "./test/fakeApi";

suite("money", () => {
    it("formats each currency in its own symbol", () => {
        expect(money(1250, "CAD")).toBe("$1,250.00");
        expect(money(500, "USD")).toBe("US$500.00");
        expect(money(1000, "JPY")).toBe("JP¥1,000");
    });

    it("signs amounts by direction", () => {
        expect(signedMoney(100, "CAD")).toBe("+$100.00");
        expect(signedMoney(-100, "CAD")).toBe("−$100.00");
    });
});

suite("totalsByCurrency", () => {
    it("never adds one currency to another", () => {
        const second = { ...CAD, id: "acc-cad-2", availableBalance: 50 };
        expect(totalsByCurrency([CAD, USD, second])).toEqual([
            { currency: "CAD", total: 1300 },
            { currency: "USD", total: 500 },
        ]);
    });

    it("uses the available balance, net of holds", () => {
        expect(totalsByCurrency([{ ...CAD, balance: 100, availableBalance: 60 }])).toEqual([{ currency: "CAD", total: 60 }]);
    });
});

suite("describe", () => {
    const transfer = { id: "t1", type: "TRANSFER", fromAccountId: CAD.id, toAccountId: "other", amount: 25, currency: "CAD" };

    it("shows money out as negative, to the other account", () => {
        expect(describe(transfer, CAD.id, [CAD])).toEqual({ label: "Transfer", detail: "To account …ther", amount: -25, currency: "CAD" });
    });

    it("shows money in as positive, from the other account by number when it is ours", () => {
        const line = describe({ ...transfer, fromAccountId: USD.id, toAccountId: CAD.id }, CAD.id, [CAD, USD]);
        expect(line.amount).toBe(25);
        expect(line.detail).toBe("From DEMO-10003");
    });

    it("credits the payee of an exchange in the currency it bought", () => {
        const fx = { ...transfer, type: "FX_TRANSFER", toAccountId: USD.id, counterAmount: 18.2, counterCurrency: "USD" };
        expect(describe(fx, USD.id, [CAD, USD])).toMatchObject({ label: "Currency exchange", amount: 18.2, currency: "USD" });
        expect(describe(fx, CAD.id, [CAD, USD])).toMatchObject({ amount: -25, currency: "CAD" });
    });

    it("labels unknown types as they are", () => {
        expect(typeLabel("OPENING_BALANCE")).toBe("Opening balance");
        expect(typeLabel("SOMETHING_NEW")).toBe("SOMETHING_NEW");
    });
});

suite("parseAmount", () => {
    it.each([["10", 10], ["10.5", 10.5], ["0.01", 0.01]])("accepts %s", (text, value) => {
        expect(parseAmount(text)).toBe(value);
    });
    it.each(["", "0", "-5", "1.234", "1e3", "abc", "0.00"])("rejects %j", (text) => {
        expect(parseAmount(text)).toBeNull();
    });
});

suite("formatDate", () => {
    it("shows Toronto time and tolerates missing values", () => {
        expect(formatDate("2026-11-22T20:04:00Z")).toContain("Nov 22, 2026");
        expect(formatDate("2026-11-22T20:04:00Z")).toMatch(/3:04/);
        expect(formatDate(undefined)).toBe("—");
        expect(formatDate("not a date")).toBe("not a date");
    });
});
