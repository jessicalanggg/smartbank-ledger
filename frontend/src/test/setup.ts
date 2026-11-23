import "@testing-library/jest-dom/vitest";
import { afterEach } from "vitest";

// jsdom does not scroll.
window.scrollTo = () => {};
import { cleanup } from "@testing-library/react";

afterEach(() => {
    cleanup();
    sessionStorage.clear();
    window.history.replaceState(null, "", "/");
});
