import type { ReactNode } from 'react'

/**
 * Every status maps to one of the four semantics, so the colour a user learns on this badge means
 * the same thing on a waterfall bar and a forecast card.
 */
export const STATUS_SEMANTIC: Record<string, 'received' | 'held' | 'lost' | 'fees' | 'neutral'> = {
  MATCHED: 'received',
  HELD_DISPUTE: 'held',
  BANK_MISSING: 'held',
  PAYMENT_FAILED: 'lost',
  REFUND_PRIOR_CYCLE: 'lost',
  BANK_DUPLICATE: 'fees',
  AMOUNT_MISMATCH: 'fees',
  UNKNOWN: 'neutral',
}

const TONE: Record<string, { color: string; background: string }> = {
  received: { color: 'var(--received)', background: 'var(--received-soft)' },
  held: { color: 'var(--held)', background: 'var(--held-soft)' },
  lost: { color: 'var(--lost)', background: 'var(--lost-soft)' },
  fees: { color: 'var(--fees)', background: 'var(--fees-soft)' },
  neutral: { color: 'var(--ink-muted)', background: 'var(--line)' },
}

export function semanticColor(status: string): string {
  return TONE[STATUS_SEMANTIC[status] ?? 'neutral'].color
}

export function StatusBadge({ status, children }: { status: string; children?: ReactNode }) {
  const tone = TONE[STATUS_SEMANTIC[status] ?? 'neutral']
  return (
    <span
      className="inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium tracking-wide"
      style={{ color: tone.color, background: tone.background }}
    >
      {children ?? status.replace(/_/g, ' ').toLowerCase()}
    </span>
  )
}
