import type { ReactNode } from "react";
import {
    ArrowLeftRight,
    BookOpen,
    Clock,
    FileText,
    History,
    Landmark,
    LogOut,
    RefreshCw,
    Wallet,
    type LucideIcon,
} from "lucide-react";
import { ROUTES, type Route } from "../router";

type NavItem = { route: Route; label: string; short: string; icon: LucideIcon };

const CUSTOMER_NAV: NavItem[] = [
    { route: "overview", label: "Overview", short: "Home", icon: Wallet },
    { route: "transactions", label: "Transactions", short: "Activity", icon: History },
    { route: "statement", label: "Statements", short: "Statement", icon: FileText },
    { route: "exchange", label: "Exchange", short: "Exchange", icon: ArrowLeftRight },
    { route: "holds", label: "Holds", short: "Holds", icon: Clock },
];

const ADMIN_NAV: NavItem[] = [{ route: "ledger", label: "Ledger", short: "Ledger", icon: BookOpen }];

export const PAGE_TEXT: Record<Route, { title: string; subtitle: string }> = {
    overview: { title: "Your accounts", subtitle: "Welcome to your SmartBank dashboard." },
    transactions: {
        title: "Transactions",
        subtitle: "Review activity for an account. Select a transaction to see its ledger entries.",
    },
    statement: { title: "Statements", subtitle: "Opening balance, every posting with a running balance, closing balance." },
    exchange: { title: "Exchange", subtitle: "Convert money between your accounts at a rate held for 60 seconds." },
    holds: { title: "Holds", subtitle: "Reserve money now, settle it later." },
    ledger: { title: "Ledger", subtitle: "The bank's books, and proof they have not been rewritten." },
};

/** The routes a signed-in user may open: customers bank, administrators read the books. */
export const routesFor = (isAdmin: boolean): Route[] => (isAdmin ? ADMIN_NAV : CUSTOMER_NAV).map((n) => n.route);

type Props = {
    route: Route;
    onNavigate: (route: Route) => void;
    isAdmin: boolean;
    email: string;
    onLogout: () => void;
    onRefresh: () => void;
    refreshing: boolean;
    children: ReactNode;
};

/** The signed-in frame: a sidebar on wide screens, a bottom tab bar on phones. */
export default function Layout({ route, onNavigate, isAdmin, email, onLogout, onRefresh, refreshing, children }: Props) {
    const nav = isAdmin ? ADMIN_NAV : CUSTOMER_NAV;
    const text = PAGE_TEXT[route];
    // Plain left clicks are handled in the app; others (new tab, copy link) use the real href.
    const link = (r: Route) => (e: React.MouseEvent) => {
        if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        e.preventDefault();
        onNavigate(r);
    };
    return (
        <div className="shell">
            <aside>
                <div className="brand">
                    <Landmark size={27} /> SmartBank
                </div>
                <nav aria-label="Main">
                    {nav.map(({ route: r, label, icon: Icon }) => (
                        <a key={r} href={ROUTES[r]} onClick={link(r)} className={`nav ${route === r ? "active" : ""}`}
                            aria-current={route === r ? "page" : undefined}>
                            <Icon size={19} /> {label}
                        </a>
                    ))}
                </nav>
                <div className="side-bottom">
                    <span>Signed in as</span>
                    <strong>{email}</strong>
                    <button type="button" className="ghost" onClick={onLogout}>
                        <LogOut size={17} /> Sign out
                    </button>
                </div>
            </aside>
            {/* On phones the sidebar is hidden; this bar takes its place. */}
            <nav className="tabbar" aria-label="Main (phone)">
                {nav.map(({ route: r, short, icon: Icon }) => (
                    <a key={r} href={ROUTES[r]} onClick={link(r)} className={route === r ? "active" : ""}
                        aria-current={route === r ? "page" : undefined}>
                        <Icon size={20} /> <span>{short}</span>
                    </a>
                ))}
            </nav>
            <main className="content">
                <header>
                    <div>
                        <div className="eyebrow">{isAdmin ? "BANK OPERATIONS" : "PERSONAL BANKING"}</div>
                        <h1>{text.title}</h1>
                        <p>{text.subtitle}</p>
                    </div>
                    <div className="header-actions">
                        <button type="button" className="outline" onClick={onRefresh} disabled={refreshing}>
                            <RefreshCw size={16} className={refreshing ? "spin" : ""} /> Refresh
                        </button>
                        <button type="button" className="outline phone-only" onClick={onLogout} aria-label="Sign out">
                            <LogOut size={16} />
                        </button>
                    </div>
                </header>
                {children}
                <footer>SmartBank demo · React + Spring Boot · Not a real financial institution</footer>
            </main>
        </div>
    );
}
