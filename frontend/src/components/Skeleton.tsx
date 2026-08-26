/** Loading is shown as the shape of the thing that is coming, never as a spinner. */
export function Skeleton({ className = '' }: { className?: string }) {
  return (
    <div
      className={`animate-pulse rounded ${className}`}
      style={{ background: 'var(--line)' }}
      aria-hidden="true"
    />
  )
}

export function SkeletonRows({ rows = 8 }: { rows?: number }) {
  return (
    <div className="space-y-2" role="status" aria-label="Loading">
      {Array.from({ length: rows }, (_, index) => (
        <Skeleton key={index} className="h-10 w-full" />
      ))}
    </div>
  )
}

export function SkeletonCards({ cards = 3 }: { cards?: number }) {
  return (
    <div className="grid gap-4" style={{ gridTemplateColumns: `repeat(${cards}, minmax(0, 1fr))` }}>
      {Array.from({ length: cards }, (_, index) => (
        <Skeleton key={index} className="h-24 w-full" />
      ))}
    </div>
  )
}
