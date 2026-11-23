import { describe, expect, it } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import TransactionDetail from "./TransactionDetail";
import { ToastProvider } from "./Toasts";
import { CAD, fakeApi } from "../test/fakeApi";

const received = { id: "tx-7", type: "TRANSFER", fromAccountId: "someone", toAccountId: CAD.id, amount: 30, currency: "CAD", createdAt: "2026-11-21T12:00:00Z" };

function details(refundable: number) {
    return { transaction: received, journal: [], seal: null, refundable };
}

describe("TransactionDetail refunds", () => {
    it("lets the payee send part of a transfer back, then shows what is left", async () => {
        let left = 30;
        const { calls } = fakeApi({
            "GET /transactions/tx-7/details": () => ({ body: details(left) }),
            "POST /transactions/tx-7/reversals": (body) => {
                left -= (body as { amount: number }).amount;
                return { status: 201, body: { id: "rev-1" } };
            },
        });
        const user = userEvent.setup();
        render(<ToastProvider><TransactionDetail token="t" transactionId="tx-7" accountId={CAD.id} accounts={[CAD]} onClose={() => {}} /></ToastProvider>);

        await user.click(await screen.findByRole("button", { name: "Refund" }));
        const amount = screen.getByLabelText("Refund amount (CAD)");
        expect(amount).toHaveValue(30);
        await user.clear(amount);
        await user.type(amount, "12");
        await user.click(screen.getByRole("button", { name: "Send refund" }));

        expect(await screen.findByText("Refunded $12.00.")).toBeInTheDocument();
        expect(await screen.findByText("Up to $18.00 can be sent back.")).toBeInTheDocument();
        expect(calls.find((c) => c.method === "POST")?.body).toMatchObject({ amount: 12 });
    });

    it("refuses more than is left without asking the server", async () => {
        const { calls } = fakeApi({ "GET /transactions/tx-7/details": details(10) });
        const user = userEvent.setup();
        render(<ToastProvider><TransactionDetail token="t" transactionId="tx-7" accountId={CAD.id} accounts={[CAD]} onClose={() => {}} /></ToastProvider>);
        await user.click(await screen.findByRole("button", { name: "Refund" }));
        await user.clear(screen.getByLabelText("Refund amount (CAD)"));
        await user.type(screen.getByLabelText("Refund amount (CAD)"), "11");
        await user.click(screen.getByRole("button", { name: "Send refund" }));
        expect(screen.getByRole("alert")).toHaveTextContent("You can refund at most $10.00.");
        expect(calls.filter((c) => c.method === "POST")).toHaveLength(0);
    });

    it("offers no refund to the sender", async () => {
        fakeApi({ "GET /transactions/tx-7/details": details(30) });
        render(<ToastProvider><TransactionDetail token="t" transactionId="tx-7" accountId="someone" accounts={[]} onClose={() => {}} /></ToastProvider>);
        await screen.findByText("Not sealed yet");
        expect(screen.queryByRole("button", { name: "Refund" })).not.toBeInTheDocument();
    });
});
