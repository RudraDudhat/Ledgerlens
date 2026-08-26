import { useCountUp } from '../lib/motion'

/**
 * The headline number counts up once so the eye lands on it, then never animates again.
 * `mono` keeps rupee amounts in the tabular face so three cards side by side stay aligned.
 */
export function StatCard({
  label,
  value,
  countUpTo,
  format,
  emphasis = false,
}: {
  label: string
  value?: string
  countUpTo?: number
  format?: (value: number) => string
  emphasis?: boolean
}) {
  const counted = useCountUp(countUpTo ?? 0)
  const shown = countUpTo !== undefined && format ? format(counted) : (value ?? '—')
  return (
    <div className="card px-5 py-4">
      <p className="text-xs uppercase tracking-wider" style={{ color: 'var(--ink-faint)' }}>
        {label}
      </p>
      <p
        className={`amount mt-2 ${emphasis ? 'text-4xl' : 'text-2xl'} font-medium`}
        style={{ textAlign: 'left', color: emphasis ? 'var(--accent)' : 'var(--ink)' }}
      >
        {shown}
      </p>
    </div>
  )
}
