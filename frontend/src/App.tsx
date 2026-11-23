import { useCallback, useEffect, useState } from "react";
import { accounts as fetchAccounts, ApiError, roleOf, transactions, type Account, type Transaction } from "./api";
import { useRoute, type Route } from "./router";
import Layout, { routesFor } from "./components/Layout";
import TransactionDetail from "./components/TransactionDetail";
import { ToastProvider, useToast } from "./components/Toasts";
import Login from "./pages/Login";
import Overview from "./pages/Overview";
import Transactions from "./pages/Transactions";
import Statement from "./pages/Statement";
import Exchange from "./pages/Exchange";
import Holds from "./pages/Holds";
import Ledger from "./pages/Ledger";

type Session = { token: string; email: string };
const SESSION_KEY = "smartbank.session";

/** True once the token's exp claim has passed (or it cannot be read). */
export function isExpired(token: string, now = Date.now()): boolean {
    try {
        const payload = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
        return typeof payload.exp === "number" && payload.exp * 1000 <= now;
    } catch {
        return true;
    }
}

/**
 * The session lives in sessionStorage, so a reload or a link to another page keeps you
 * signed in, while closing the tab signs you out. The token is short-lived.
 */
function savedSession(): Session | null {
    try {
        const s = JSON.parse(sessionStorage.getItem(SESSION_KEY) ?? "null") as Session | null;
        return s && !isExpired(s.token) ? s : null;
    } catch {
        return null;
    }
}

export default function App() {
    return (
        <ToastProvider>
            <Bank />
        </ToastProvider>
    );
}

function Bank() {
    const toast = useToast();
    const [session, setSession] = useState<Session | null>(savedSession);
    const [route, navigate] = useRoute();
    const [items, setItems] = useState<Account[]>([]);
    const [loaded, setLoaded] = useState(false);
    const [selected, setSelected] = useState("");
    // Kept with the account it belongs to, so switching accounts shows a placeholder,
    // not the previous account's transactions, while a reload after a change does not.
    const [loadedHistory, setHistory] = useState<{ accountId: string; list: Transaction[] } | null>(null);
    const history = loadedHistory?.accountId === selected ? loadedHistory.list : null;
    const [refreshKey, setRefreshKey] = useState(0);
    const [refreshing, setRefreshing] = useState(false);
    const [openTransaction, setOpenTransaction] = useState<string | null>(null);

    const token = session?.token ?? null;
    const isAdmin = token !== null && roleOf(token) === "ADMIN";

    const logout = useCallback(() => {
        try { sessionStorage.removeItem(SESSION_KEY); } catch { /* storage unavailable */ }
        setSession(null);
        setItems([]);
        setLoaded(false);
        setHistory(null);
        setSelected("");
        setOpenTransaction(null);
    }, []);

    const fail = useCallback((e: unknown) => {
        if (e instanceof ApiError && e.status === 401) {
            logout();
            toast.error("Your session has ended. Sign in again.");
        } else toast.error((e as Error).message);
    }, [logout, toast]);

    const load = useCallback(async () => {
        if (!token || isAdmin) return;
        setRefreshing(true);
        try {
            const list = await fetchAccounts(token);
            setItems(list);
            setSelected((prev) => (list.some((a) => a.id === prev) ? prev : (list[0]?.id ?? "")));
            setLoaded(true);
        } catch (e) {
            fail(e);
        } finally {
            setRefreshing(false);
        }
    }, [token, isAdmin, fail]);

    useEffect(() => {
        void load();
    }, [load]);

    // The selected account's transactions, reloaded whenever balances may have changed.
    useEffect(() => {
        if (!token || !selected) {
            setHistory(null);
            return;
        }
        let cancelled = false;
        transactions(token, selected)
            .then((list) => !cancelled && setHistory({ accountId: selected, list }))
            .catch((e) => !cancelled && fail(e));
        return () => {
            cancelled = true;
        };
    }, [token, selected, items, fail]);

    // Customers bank; administrators read the books. Send each to a page they can use.
    const allowed = routesFor(isAdmin);
    useEffect(() => {
        const routes = routesFor(isAdmin);
        if (token && !routes.includes(route)) navigate(routes[0], true);
    }, [token, isAdmin, route, navigate]);

    function signedIn(newToken: string, email: string) {
        const s = { token: newToken, email };
        try { sessionStorage.setItem(SESSION_KEY, JSON.stringify(s)); } catch { /* storage unavailable */ }
        setSession(s);
        navigate(roleOf(newToken) === "ADMIN" ? "ledger" : "overview", true);
    }

    function refresh() {
        setRefreshKey((k) => k + 1);
        void load();
    }

    if (!token || !session) return <Login onLogin={signedIn} />;

    const changed = () => void load();
    const page: Record<Route, React.ReactNode> = {
        overview: (
            <Overview token={token} accounts={items} loaded={loaded} selected={selected} onSelect={setSelected}
                history={history} onChanged={changed} onOpenTransaction={setOpenTransaction} />
        ),
        transactions: (
            <Transactions accounts={items} selected={selected} onSelect={setSelected} history={history}
                onOpenTransaction={setOpenTransaction} />
        ),
        statement: (
            <Statement token={token} accounts={items} selected={selected} onSelect={setSelected}
                refreshKey={refreshKey} onOpenTransaction={setOpenTransaction} />
        ),
        exchange: <Exchange key={refreshKey} token={token} accounts={items} onChanged={changed} />,
        holds: (
            <Holds key={refreshKey} token={token} accounts={items} selected={selected} onSelect={setSelected}
                onChanged={load} />
        ),
        ledger: <Ledger key={refreshKey} token={token} />,
    };

    return (
        <Layout route={route} onNavigate={navigate} isAdmin={isAdmin} email={session.email} onLogout={logout}
            onRefresh={refresh} refreshing={refreshing}>
            {allowed.includes(route) && page[route]}
            {openTransaction && (
                <TransactionDetail
                    token={token}
                    transactionId={openTransaction}
                    accountId={selected}
                    accounts={items}
                    onClose={() => setOpenTransaction(null)}
                    onChanged={changed}
                />
            )}
        </Layout>
    );
}
