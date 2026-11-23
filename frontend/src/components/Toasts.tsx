import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from "react";
import { CheckCircle2, X, XCircle } from "lucide-react";

type Toast = { id: number; kind: "success" | "error"; text: string };
type Notify = { success: (text: string) => void; error: (text: string) => void };

const ToastContext = createContext<Notify>({ success: () => {}, error: () => {} });

/** Short-lived messages in the corner: successes fade after 4 s, errors after 8 s. */
export function ToastProvider({ children }: { children: ReactNode }) {
    const [toasts, setToasts] = useState<Toast[]>([]);
    const next = useRef(1);
    const dismiss = useCallback((id: number) => setToasts((all) => all.filter((t) => t.id !== id)), []);
    const show = useCallback(
        (kind: Toast["kind"], text: string) => {
            const id = next.current++;
            setToasts((all) => [...all.slice(-2), { id, kind, text }]);
            setTimeout(() => dismiss(id), kind === "success" ? 4000 : 8000);
        },
        [dismiss],
    );
    const notify = useMemo<Notify>(
        () => ({ success: (text) => show("success", text), error: (text) => show("error", text) }),
        [show],
    );
    return (
        <ToastContext.Provider value={notify}>
            {children}
            <div className="toasts" aria-live="polite">
                {toasts.map((t) => (
                    <div key={t.id} className={`toast ${t.kind}`} role={t.kind === "error" ? "alert" : "status"}>
                        {t.kind === "success" ? <CheckCircle2 size={18} /> : <XCircle size={18} />}
                        <span>{t.text}</span>
                        <button type="button" aria-label="Dismiss" onClick={() => dismiss(t.id)}>
                            <X size={15} />
                        </button>
                    </div>
                ))}
            </div>
        </ToastContext.Provider>
    );
}

export const useToast = () => useContext(ToastContext);
