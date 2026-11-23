import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import TransferForm from "./TransferForm";
import { ToastProvider } from "./Toasts";
import { CAD, fakeApi } from "../test/fakeApi";

function renderForm() {
    const onDone = vi.fn();
    render(<ToastProvider><TransferForm token="t" account={CAD} onDone={onDone} /></ToastProvider>);
    return { onDone, user: userEvent.setup() };
}

async function fill(user: ReturnType<typeof userEvent.setup>, to: string, amount: string) {
    await user.type(screen.getByLabelText("Destination account number"), to);
    await user.type(screen.getByLabelText("Amount (CAD)"), amount);
    await user.click(screen.getByRole("button", { name: "Review transfer" }));
}

describe("TransferForm", () => {
    it("refuses more than the available balance before asking the server", async () => {
        const { calls } = fakeApi({});
        const { user } = renderForm();
        await fill(user, "DEMO-10002", "5000");
        expect(screen.getByRole("alert")).toHaveTextContent("exceeds your available balance");
        expect(calls).toHaveLength(0);
    });

    it("moves no money until confirmed", async () => {
        const { calls } = fakeApi({});
        const { user } = renderForm();
        await fill(user, "DEMO-10002", "25");
        expect(screen.getByRole("region", { name: "Transfer confirmation" })).toHaveTextContent("No money has moved yet");
        await user.click(screen.getByRole("button", { name: "Edit / Cancel" }));
        expect(calls).toHaveLength(0);
    });

    it("retries a failed transfer with the same request key, so it cannot pay twice", async () => {
        let attempts = 0;
        const { calls } = fakeApi({
            "POST /transfers/by-account-number": () => attempts++ === 0
                ? { status: 503, body: { detail: "Service unavailable" } }
                : { status: 201, body: { id: "tx-9", type: "TRANSFER", amount: 25, currency: "CAD", createdAt: "2026-11-22T12:00:00Z" } },
        });
        const { user, onDone } = renderForm();
        await fill(user, "DEMO-10002", "25");
        await user.click(screen.getByRole("button", { name: "Confirm transfer" }));
        expect(await screen.findByText(/Service unavailable/)).toBeInTheDocument();
        await user.click(screen.getByRole("button", { name: "Retry same transfer" }));

        expect(await screen.findByText("✓ Transfer completed")).toBeInTheDocument();
        const keys = calls.map((c) => (c.body as { requestKey: string }).requestKey);
        expect(keys).toHaveLength(2);
        expect(keys[0]).toBe(keys[1]);
        expect(onDone).toHaveBeenCalledOnce();
        expect(screen.getByText("Sent $25.00 to DEMO-10002.")).toBeInTheDocument();
    });
});
