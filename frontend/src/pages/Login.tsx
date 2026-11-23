import { useState } from "react";
import { Landmark, ShieldCheck } from "lucide-react";
import { login } from "../api";

/** Sign-in. Passes the access token and email up once the server accepts them. */
export default function Login({ onLogin }: { onLogin: (token: string, email: string) => void }) {
    const [email, setEmail] = useState("demo.a@smartbank.local");
    const [password, setPassword] = useState("");
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState("");

    async function submit(e: React.FormEvent) {
        e.preventDefault();
        setLoading(true);
        setError("");
        try {
            const r = await login(email, password);
            onLogin(r.accessToken, email);
        } catch (err) {
            setError((err as Error).message);
            setLoading(false);
        }
    }

    return (
        <main className="login-page">
            <section className="login-card">
                <div className="brand">
                    <Landmark size={28} /> SmartBank
                </div>
                <div className="eyebrow">CUSTOMER PORTAL</div>
                <h1>Welcome back</h1>
                <p>Sign in to view your accounts and manage your money.</p>
                <form onSubmit={submit}>
                    <label>
                        Email address
                        <input autoComplete="username" type="email" required value={email}
                            onChange={(e) => setEmail(e.target.value)} />
                    </label>
                    <label>
                        Password
                        <input autoComplete="current-password" type="password" required value={password}
                            onChange={(e) => setPassword(e.target.value)} placeholder="Enter your demo password" />
                    </label>
                    {error && <div className="error" role="alert">{error}</div>}
                    <button disabled={loading}>{loading ? "Signing in…" : "Sign in securely"}</button>
                </form>
                <div className="fine">
                    <ShieldCheck size={16} /> Demo environment · No real funds
                </div>
            </section>
        </main>
    );
}
