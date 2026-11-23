import { describe, expect, it } from "vitest";
import { accounts, ApiError, roleOf } from "./api";
import { fakeApi, fakeToken } from "./test/fakeApi";

describe("api", () => {
    it("shows the server's explanation instead of raw JSON", async () => {
        fakeApi({ "GET /accounts": () => ({ status: 409, body: { title: "Rejected", detail: "Insufficient funds" } }) });
        const error = await accounts("t").catch((e: unknown) => e);
        expect(error).toBeInstanceOf(ApiError);
        expect((error as ApiError).status).toBe(409);
        expect((error as ApiError).message).toBe("Insufficient funds");
    });

    it("explains an expired session", async () => {
        fakeApi({ "GET /accounts": () => ({ status: 401 }) });
        await expect(accounts("t")).rejects.toThrow("Your session has ended");
    });

    it("sends the bearer token", async () => {
        const { fetch } = fakeApi({ "GET /accounts": [] });
        await accounts("abc");
        const headers = fetch.mock.calls[0][1]?.headers as Headers;
        expect(headers.get("Authorization")).toBe("Bearer abc");
    });

    it("reads the role from the token, defaulting to customer", () => {
        expect(roleOf(fakeToken({ role: "ADMIN" }))).toBe("ADMIN");
        expect(roleOf(fakeToken({ role: undefined }))).toBe("CUSTOMER");
        expect(roleOf("garbage")).toBe("CUSTOMER");
    });
});
