import { semanticColor, statusLabel } from './StatusBadge'

/** One slim bar for the whole batch: width is share of records, colour is the shared semantic. */
export function StatusStrip({ counts }: { counts: Record<string, number> }) {
  const entries = Object.entries(counts).filter(([, count]) => count > 0)
  const total = entries.reduce((sum, [, count]) => sum + count, 0)
  if (total === 0) return null

  return (
    <div>
      <div className="flex h-2 w-full overflow-hidden rounded-full" style={{ background: 'var(--line)' }}>
        {entries.map(([status, count]) => (
          <div
            key={status}
            style={{ width: `${(count / total) * 100}%`, background: semanticColor(status) }}
            title={`${statusLabel(status)}: ${count}`}
          />
        ))}
      </div>
      <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
        {entries.map(([status, count]) => (
          <span key={status} className="flex items-center gap-1.5 text-xs" style={{ color: 'var(--ink-muted)' }}>
            <span className="h-2 w-2 rounded-full" style={{ background: semanticColor(status) }} />
            {statusLabel(status)} <span className="ref">{count}</span>
          </span>
        ))}
      </div>
    </div>
  )
}
