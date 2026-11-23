import { describe, expect, it, vi } from "vitest";
import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import Exchange, { secondsLeft } from "./Exchange";
import { ToastProvider } from "../components/Toasts";
import { CAD, fakeApi, USD } from "../test/fakeApi";

const quote = (expiresAt: string) => ({
    id: "q-1", fromAccountId: CAD.id, toAccountId: USD.id, sellAmount: 100, sellCurrency: "CAD",
    buyAmount: 72.5, buyCurrency: "USD", rate: 0.725, expiresAt,
});

describe("Exchange", () => {
    it("counts whole seconds down to zero", () => {
        expect(secondsLeft("2026-01-01T00:01:00Z", Date.parse("2026-01-01T00:00:00.400Z"))).toBe(60);
        expect(secondsLeft("2026-01-01T00:01:00Z", Date.parse("2026-01-01T00:02:00Z"))).toBe(0);
    });

    it("quotes, then converts at exactly the quoted rate", async () => {
        const { calls } = fakeApi({
            "GET /fx/rates": [{ id: "r", baseCurrency: "CAD", quoteCurrency: "USD", rate: 0.725, setAt: "2026-11-22T00:00:00Z" }],
            "POST /fx/quotes": () => ({ status: 201, body: quote(new Date(Date.now() + 60_000).toISOString()) }),
            "POST /fx/transfers": () => ({ status: 201, body: { id: "tx-fx" } }),
        });
        const onChanged = vi.fn();
        const user = userEvent.setup();
        render(<ToastProvider><Exchange token="t" accounts={[CAD, USD]} onChanged={onChanged} /></ToastProvider>);

        await user.type(screen.getByLabelText("Amount to convert (CAD)"), "100");
        await user.click(screen.getByRole("button", { name: "Get quote" }));
        expect(await screen.findByText("US$72.50")).toBeInTheDocument();
        expect(screen.getByText("1 CAD = 0.725 USD")).toBeInTheDocument();
        expect(screen.getByText(/Rate held for (59|60)s/)).toBeInTheDocument();
        expect(calls.find((c) => c.path === "/fx/quotes")?.body).toEqual({ fromAccountId: CAD.id, toAccountId: USD.id, amount: 100 });

        await user.click(screen.getByRole("button", { name: "Confirm exchange" }));
        expect(await screen.findByText("Converted $100.00 into US$72.50.")).toBeInTheDocument();
        expect(calls.find((c) => c.path === "/fx/transfers")?.body).toMatchObject({ quoteId: "q-1" });
        expect(onChanged).toHaveBeenCalledOnce();
    });

    it("stops a quote from being used once it expires", async () => {
        vi.useFakeTimers({ shouldAdvanceTime: true });
        try {
            fakeApi({
                "GET /fx/rates": [],
                "POST /fx/quotes": () => ({ status: 201, body: quote(new Date(Date.now() + 2_000).toISOString()) }),
            });
            const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
            render(<ToastProvider><Exchange token="t" accounts={[CAD, USD]} onChanged={() => {}} /></ToastProvider>);
            await user.type(screen.getByLabelText("Amount to convert (CAD)"), "100");
            await user.click(screen.getByRole("button", { name: "Get quote" }));
            await screen.findByRole("button", { name: "Confirm exchange" });

            await act(async () => { vi.advanceTimersByTime(3_000); });
            expect(screen.getByText(/This quote has expired/)).toBeInTheDocument();
            expect(screen.queryByRole("button", { name: "Confirm exchange" })).not.toBeInTheDocument();
            expect(screen.getByRole("button", { name: "Get a new quote" })).toBeInTheDocument();
        } finally {
            vi.useRealTimers();
        }
    });

    it("explains when there is nothing to exchange between", () => {
        fakeApi({ "GET /fx/rates": [] });
        render(<ToastProvider><Exchange token="t" accounts={[CAD]} onChanged={() => {}} /></ToastProvider>);
        expect(screen.getByText("You need accounts in two currencies")).toBeInTheDocument();
    });
});
