import { useCallback, useSyncExternalStore } from "react";

/** The app's pages and their URLs. */
export const ROUTES = {
    overview: "/",
    transactions: "/transactions",
    statement: "/statement",
    exchange: "/exchange",
    holds: "/holds",
    ledger: "/ledger",
} as const;

export type Route = keyof typeof ROUTES;

export function routeOf(pathname: string): Route {
    const path = pathname.replace(/\/+$/, "") || "/";
    const match = (Object.keys(ROUTES) as Route[]).find((r) => ROUTES[r] === path);
    return match ?? "overview";
}

const NAVIGATE = "smartbank:navigate";

function subscribe(onChange: () => void) {
    window.addEventListener("popstate", onChange);
    window.addEventListener(NAVIGATE, onChange);
    return () => {
        window.removeEventListener("popstate", onChange);
        window.removeEventListener(NAVIGATE, onChange);
    };
}

/**
 * The current page, kept in the address bar so the back button, reloads and links
 * work. `replace` changes the URL without adding a history entry (for redirects).
 */
export function useRoute(): [Route, (route: Route, replace?: boolean) => void] {
    const pathname = useSyncExternalStore(subscribe, () => window.location.pathname);
    const go = useCallback((route: Route, replace = false) => {
        if (window.location.pathname === ROUTES[route]) return;
        if (replace) window.history.replaceState(null, "", ROUTES[route]);
        else window.history.pushState(null, "", ROUTES[route]);
        window.dispatchEvent(new Event(NAVIGATE));
        window.scrollTo?.(0, 0);
    }, []);
    return [routeOf(pathname), go];
}
