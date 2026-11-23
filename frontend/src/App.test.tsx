import { describe, expect, it } from "vitest";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App, { isExpired } from "./App";
import { CAD, fakeApi, fakeToken, USD } from "./test/fakeApi";

const deposit = { id: "tx-1", type: "DEPOSIT", fromAccountId: null, toAccountId: CAD.id, amount: 40, currency: "CAD", createdAt: "2026-11-20T15:00:00Z" };

function customerBank(token = fakeToken()) {
    return fakeApi({
        "POST /auth/login": { accessToken: token, tokenType: "Bearer", expiresIn: 900 },
        "GET /accounts": [CAD, USD],
        [`GET /accounts/${CAD.id}/transactions`]: [deposit],
        [`GET /accounts/${USD.id}/transactions`]: [],
        "GET /transactions/tx-1/details": {
            transaction: deposit,
            journal: [
                { code: "1000", name: "Cash and settlement", type: "ASSET", debit: 40, credit: 0, currency: "CAD" },
                { code: "2000-DEMO-10001", name: "DEMO-10001", type: "LIABILITY", debit: 0, credit: 40, currency: "CAD" },
            ],
            seal: { seq: 3, sealedAt: "2026-11-20T15:00:05Z", signed: true, keyId: "d0777bff" },
        },
    });
}

async function signIn(user = userEvent.setup()) {
    render(<App />);
    await user.type(screen.getByLabelText("Password"), "LocalDemoPass123!");
    await user.click(screen.getByRole("button", { name: "Sign in securely" }));
    await screen.findByText("DEMO-10003");
    return user;
}

describe("App", () => {
    it("signs in and shows a separate total for each currency", async () => {
        const { calls } = customerBank();
        await signIn();
        expect(calls[0]).toMatchObject({ method: "POST", path: "/auth/login", body: { email: "demo.a@smartbank.local" } });
        const summary = screen.getByText("TOTAL AVAILABLE BALANCE").parentElement!;
        expect(summary).toHaveTextContent("$1,250.00 CAD");
        expect(summary).toHaveTextContent("+ US$500.00 USD");
        expect(summary).toHaveTextContent("in 2 currencies, shown separately");
    });

    it("shows a rejected sign-in without leaving the page", async () => {
        fakeApi({ "POST /auth/login": () => ({ status: 401, body: { title: "Authentication failed", detail: "Invalid email or password" } }) });
        const user = userEvent.setup();
        render(<App />);
        await user.type(screen.getByLabelText("Password"), "wrong");
        await user.click(screen.getByRole("button", { name: "Sign in securely" }));
        expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password");
    });

    it("gives each page a URL and opens a transaction's ledger entries", async () => {
        customerBank();
        const user = await signIn();
        await user.click(screen.getAllByRole("link", { name: /Transactions/ })[0]);
        expect(window.location.pathname).toBe("/transactions");
        expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent("Transactions");

        await user.click(await screen.findByText("Into this account"));
        const drawer = await screen.findByRole("dialog", { name: "Transaction details" });
        expect(await within(drawer).findByText("Sealed in seal #3")).toBeInTheDocument();
        expect(within(drawer).getByText("1000")).toBeInTheDocument();
        expect(within(drawer).getAllByText("$40.00")).toHaveLength(4); // both lines, both totals
        await user.keyboard("{Escape}");
        expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });

    it("keeps the session across a reload and sends customers away from the ledger", async () => {
        customerBank();
        sessionStorage.setItem("smartbank.session", JSON.stringify({ token: fakeToken(), email: "demo.a@smartbank.local" }));
        window.history.replaceState(null, "", "/ledger");
        render(<App />);
        expect(await screen.findByText("DEMO-10003")).toBeInTheDocument();
        expect(window.location.pathname).toBe("/");
    });

    it("signs out when the server says the session has ended", async () => {
        fakeApi({ "GET /accounts": () => ({ status: 401 }) });
        sessionStorage.setItem("smartbank.session", JSON.stringify({ token: fakeToken(), email: "a@b.c" }));
        render(<App />);
        expect(await screen.findByRole("button", { name: "Sign in securely" })).toBeInTheDocument();
        expect(await screen.findByText("Your session has ended. Sign in again.")).toBeInTheDocument();
    });

    it("treats an expired or unreadable token as signed out", () => {
        expect(isExpired(fakeToken({ exp: Math.floor(Date.now() / 1000) - 1 }))).toBe(true);
        expect(isExpired(fakeToken())).toBe(false);
        expect(isExpired("garbage")).toBe(true);
    });
});
