/** Grey placeholder bars shown while data loads, shaped like what is coming. */
export function Skeleton({ lines = 3, height = 14 }: { lines?: number; height?: number }) {
    return (
        <div className="skeleton" aria-busy="true" aria-label="Loading">
            {Array.from({ length: lines }, (_, i) => (
                <span key={i} style={{ height, width: `${92 - ((i * 17) % 40)}%` }} />
            ))}
        </div>
    );
}

export function AccountCardSkeleton() {
    return (
        <div className="account-card skeleton-card" aria-hidden="true">
            <Skeleton lines={3} height={16} />
        </div>
    );
}

/** A friendly empty state: an icon, what is missing, and what to do about it. */
export function Empty({ icon, title, children }: { icon?: React.ReactNode; title: string; children?: React.ReactNode }) {
    return (
        <div className="empty-state">
            {icon}
            <strong>{title}</strong>
            {children && <span>{children}</span>}
        </div>
    );
}
