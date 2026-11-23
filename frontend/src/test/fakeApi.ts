import { vi } from "vitest";
import type { Account } from "../api";

/** An unsigned JWT with the given claims; the frontend only reads them for display. */
export function fakeToken(claims: Record<string, unknown> = {}): string {
    const part = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, "").replace(/\+/g, "-").replace(/\//g, "_");
    return `${part({ alg: "none" })}.${part({ role: "CUSTOMER", exp: Math.floor(Date.now() / 1000) + 3600, ...claims })}.sig`;
}

export const CAD: Account = {
    id: "acc-cad", accountNumber: "DEMO-10001", ownerName: "Demo A", accountType: "CHECKING", currency: "CAD",
    balance: 1250, availableBalance: 1250, pendingDebits: 0, pendingCredits: 0,
};
export const USD: Account = {
    id: "acc-usd", accountNumber: "DEMO-10003", ownerName: "Demo A", accountType: "SAVINGS", currency: "USD",
    balance: 500, availableBalance: 500, pendingDebits: 0, pendingCredits: 0,
};

type Handler = (body: unknown, init: RequestInit) => { status?: number; body?: unknown };
export type Call = { method: string; path: string; body: unknown };

/**
 * Replaces fetch with canned answers keyed by "METHOD /path" (path without /api/v1 and
 * without the query string). Records every call; unknown routes answer 404.
 */
export function fakeApi(routes: Record<string, Handler | object>) {
    const calls: Call[] = [];
    const fetch = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
        const url = new URL(String(input), "http://localhost");
        const method = (init.method ?? "GET").toUpperCase();
        const path = url.pathname.replace(/^\/api\/v1/, "");
        const body = init.body ? JSON.parse(String(init.body)) : undefined;
        calls.push({ method, path, body });
        const route = routes[`${method} ${path}`];
        if (route === undefined)
            return new Response(JSON.stringify({ title: "Not found", detail: `No fake for ${method} ${path}` }), { status: 404 });
        const answer = typeof route === "function" ? (route as Handler)(body, init) : { body: route };
        const status = answer.status ?? 200;
        return new Response(answer.body === undefined ? null : JSON.stringify(answer.body), { status });
    });
    vi.stubGlobal("fetch", fetch);
    return { calls, fetch };
}
