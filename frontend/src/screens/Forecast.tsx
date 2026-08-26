import { motion } from 'framer-motion'
import { useEffect, useMemo, useState } from 'react'
import { api, type ForecastEntry } from '../api/client'
import { Skeleton } from '../components/Skeleton'
import { EmptyState, ErrorState } from '../components/States'
import { addDays, isWeekend, rupees, shortDate, todayIso, weekday } from '../lib/format'
import { BAR_STAGGER_SECONDS, pageTransition, usePrefersReducedMotion } from '../lib/motion'

const METHOD_COLOR: Record<string, string> = {
  UPI: 'var(--received)',
  CARD: 'var(--accent)',
  NETBANKING: 'var(--fees)',
  WALLET: 'var(--held)',
}

const STRIP_DAYS = 14

export function Forecast({ batchId }: { batchId: string }) {
  const [entries, setEntries] = useState<ForecastEntry[] | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [asOf, setAsOf] = useState(todayIso())
  const reduced = usePrefersReducedMotion()

  useEffect(() => {
    let cancelled = false
    setEntries(null)
    setError(null)
    api
      .forecast(batchId)
      .then((loaded) => {
        if (cancelled) return
        setEntries(loaded)
        // Anchor the strip on the batch itself rather than on the wall clock, which may be far away.
        if (loaded.length > 0) setAsOf(loaded[0].date)
      })
      .catch((loadError) => !cancelled && setError(loadError))
    return () => {
      cancelled = true
    }
  }, [batchId])

  const days = useMemo(() => {
    const byDate = new Map((entries ?? []).map((entry) => [entry.date, entry]))
    return Array.from({ length: STRIP_DAYS }, (_, offset) => {
      const date = addDays(asOf, offset)
      return { date, entry: byDate.get(date) ?? null }
    })
  }, [entries, asOf])

  const held = useMemo(() => (entries ?? []).filter((entry) => entry.heldAmount > 0), [entries])
  const heldTotal = held.reduce((sum, entry) => sum + entry.heldAmount, 0)

  if (error != null) return <div className="p-10"><ErrorState error={error} /></div>

  if (!entries) {
    return (
      <div className="space-y-4 p-10">
        <Skeleton className="h-8 w-48" />
        <div className="flex gap-2">
          {Array.from({ length: 7 }, (_, index) => (
            <Skeleton key={index} className="h-32 w-32" />
          ))}
        </div>
      </div>
    )
  }

  return (
    <motion.div {...pageTransition} className="p-8">
      <header className="flex items-end justify-between">
        <div>
          <h1 className="text-lg font-semibold">Upcoming settlements</h1>
          <p className="mt-1 text-sm" style={{ color: 'var(--ink-muted)' }}>
            What this batch says is still to come. Money with no known release date is left off rather than guessed at.
          </p>
        </div>
        <label className="text-xs" style={{ color: 'var(--ink-muted)' }}>
          As of{' '}
          <input
            type="date"
            value={asOf}
            onChange={(event) => setAsOf(event.target.value)}
            className="ref ml-1 rounded-lg border px-2 py-1"
            style={{ background: 'var(--surface-raised)', borderColor: 'var(--line)', color: 'var(--ink)' }}
          />
        </label>
      </header>

      {entries.length === 0 ? (
        <div className="mt-6">
          <EmptyState
            title="Nothing is expected"
            body="Every payment in this batch has already settled, and no dispute has a known release date."
          />
        </div>
      ) : (
        <div className="mt-6 flex gap-2 overflow-x-auto pb-2">
          {days.map((day, index) => (
            <DayCard key={day.date} date={day.date} entry={day.entry} index={index} reduced={reduced} today={asOf} />
          ))}
        </div>
      )}

      {held.length > 0 && (
        <div className="card mt-6 max-w-md p-5">
          <h2 className="text-sm font-semibold">Held for disputes</h2>
          <p className="amount mt-1 text-2xl" style={{ textAlign: 'left', color: 'var(--held)' }}>
            {rupees(heldTotal)}
          </p>
          <ul className="mt-3 space-y-1">
            {held.map((entry) => (
              <li key={entry.date} className="flex items-center justify-between text-xs">
                <span style={{ color: 'var(--ink-muted)' }}>expected {shortDate(entry.date)}</span>
                <span className="amount">{rupees(entry.heldAmount)}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </motion.div>
  )
}

function DayCard({
  date,
  entry,
  index,
  reduced,
  today,
}: {
  date: string
  entry: ForecastEntry | null
  index: number
  reduced: boolean
  today: string
}) {
  const weekend = isWeekend(date)
  const isToday = date === today
  const methods = Object.entries(entry?.breakdownByMethod ?? {})
  const total = methods.reduce((sum, [, amount]) => sum + amount, 0)

  return (
    <motion.div
      initial={reduced ? false : { opacity: 0, y: 6 }}
      animate={{ opacity: weekend && !entry ? 0.45 : 1, y: 0 }}
      transition={{ duration: 0.2, delay: reduced ? 0 : index * BAR_STAGGER_SECONDS }}
      className="card w-[132px] shrink-0 p-3"
      style={{ borderColor: isToday ? 'var(--accent)' : 'var(--line)' }}
    >
      <p className="text-xs" style={{ color: isToday ? 'var(--accent)' : 'var(--ink-faint)' }}>
        {weekday(date)} {shortDate(date)}
        {isToday && ' · today'}
      </p>

      {entry ? (
        <>
          <p className="amount mt-2 text-sm font-medium" style={{ textAlign: 'left' }}>
            {rupees(entry.expectedAmount)}
          </p>
          <div className="mt-2 flex h-1.5 overflow-hidden rounded-full" style={{ background: 'var(--line)' }}>
            {methods.map(([method, amount]) => (
              <div
                key={method}
                style={{ width: `${(amount / total) * 100}%`, background: METHOD_COLOR[method] ?? 'var(--ink-faint)' }}
                title={`${method}: ${rupees(amount)}`}
              />
            ))}
          </div>
          <p className="mt-2 text-[11px]" style={{ color: 'var(--ink-faint)' }}>
            {methods.map(([method]) => method.toLowerCase()).join(', ')}
          </p>
        </>
      ) : (
        <p className="mt-2 text-xs" style={{ color: 'var(--ink-faint)' }}>
          {weekend ? 'weekend' : '—'}
        </p>
      )}
    </motion.div>
  )
}
