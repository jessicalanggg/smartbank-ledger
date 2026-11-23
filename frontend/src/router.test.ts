import { describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { routeOf, useRoute } from "./router";

describe("router", () => {
    it("maps paths to pages, ignoring a trailing slash, and unknown paths to the overview", () => {
        expect(routeOf("/")).toBe("overview");
        expect(routeOf("/statement/")).toBe("statement");
        expect(routeOf("/nope")).toBe("overview");
    });

    it("keeps the page in the address bar and follows the back button", () => {
        const { result } = renderHook(() => useRoute());
        act(() => result.current[1]("holds"));
        expect(window.location.pathname).toBe("/holds");
        expect(result.current[0]).toBe("holds");

        act(() => {
            window.history.replaceState(null, "", "/exchange");
            window.dispatchEvent(new PopStateEvent("popstate"));
        });
        expect(result.current[0]).toBe("exchange");
    });
});
