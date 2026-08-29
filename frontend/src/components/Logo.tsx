/**
 * The mark: a lens over three ledger rows, open where the row that does not fit passes through it.
 *
 * <p>The two halves of the name are the two halves of the drawing. The rows are a ledger, and the
 * ring is the lens held over it — but the ring is deliberately not closed. What the product actually
 * does is find the record that does not reconcile, so the middle row is the one drawn at full
 * strength, and it is the one that breaks the circle on its way out. A closed ring would have been a
 * tidier logo about a tool that finds nothing.
 *
 * <p>Accent only, in two weights. The four semantic colours mean received, held, lost and fees
 * everywhere else, and a logo is the one place in this app with nothing to report.
 */
export function Logo({ size = 28, title }: { size?: number; title?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      role={title ? 'img' : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    >
      {/* The long way round, leaving a gap on the right for the row to exit through. */}
      <path
        d="M25.61 18.98 A 11.5 11.5 0 1 1 25.61 13.02"
        stroke="var(--accent)"
        strokeWidth="2.4"
        strokeLinecap="round"
      />
      <rect x="7" y="10.6" width="13" height="2.4" rx="1.2" fill="var(--accent)" opacity="0.4" />
      <rect x="7" y="14.8" width="24" height="2.4" rx="1.2" fill="var(--accent)" />
      <rect x="7" y="19" width="10.5" height="2.4" rx="1.2" fill="var(--accent)" opacity="0.4" />
    </svg>
  )
}
