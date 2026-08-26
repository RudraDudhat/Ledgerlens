import { motion } from 'framer-motion'
import { useEffect, useMemo, useState } from 'react'
import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis } from 'recharts'
import { api, type WaterfallStep } from '../api/client'
import { Skeleton, SkeletonCards } from '../components/Skeleton'
import { ErrorState } from '../components/States'
import { rupees, signedRupees } from '../lib/format'
import { BAR_STAGGER_SECONDS, pageTransition, usePrefersReducedMotion } from '../lib/motion'

type Plotted = WaterfallStep & { base: number; magnitude: number; running: number; color: string }

const SEMANTIC: Record<string, string> = {
  'Gross sales': 'var(--accent)',
  'Failed payments': 'var(--lost)',
  'Razorpay fees': 'var(--fees)',
  'GST on fees': 'var(--fees)',
  'Held for disputes': 'var(--held)',
  Refunds: 'var(--lost)',
  'Settlements not credited by bank': 'var(--held)',
  'Unmatched bank credits': 'var(--received)',
  'Bank amount differences': 'var(--fees)',
  'Unexplained settlement difference': 'var(--lost)',
}

export function Waterfall({
  batchId,
  onOpenRows,
  onAskAbout,
}: {
  batchId: string
  onOpenRows: (title: string, rowIds: number[]) => void
  onAskAbout: (question: string) => void
}) {
  const [steps, setSteps] = useState<WaterfallStep[] | null>(null)
  const [narrative, setNarrative] = useState<string | null>(null)
  const [narrativeError, setNarrativeError] = useState<unknown>(null)
  const [error, setError] = useState<unknown>(null)
  const [bankCredits, setBankCredits] = useState<number | null>(null)
  const reduced = usePrefersReducedMotion()

  useEffect(() => {
    let cancelled = false
    setSteps(null)
    setNarrative(null)
    setNarrativeError(null)
    setError(null)

    Promise.all([api.waterfall(batchId), api.summary(batchId)])
      .then(([loadedSteps, summary]) => {
        if (cancelled) return
        setSteps(loadedSteps)
        setBankCredits(summary.totalBankCredits)
      })
      .catch((loadError) => !cancelled && setError(loadError))

    // The narration is a separate call so a missing API key costs the numbers nothing.
    api
      .narrative(batchId)
      .then((response) => !cancelled && setNarrative(response.narrative))
      .catch((error) => !cancelled && setNarrativeError(error))

    return () => {
      cancelled = true
    }
  }, [batchId])

  const plotted = useMemo<Plotted[]>(() => {
    if (!steps) return []
    let running = 0
    return steps.map((step) => {
      const start = running
      running += step.amount
      return {
        ...step,
        base: Math.min(start, running),
        magnitude: Math.abs(step.amount),
        running,
        color: SEMANTIC[step.label] ?? 'var(--ink-muted)',
      }
    })
  }, [steps])

  const walked = plotted.length > 0 ? plotted[plotted.length - 1].running : 0
  const drift = bankCredits === null ? null : Number((walked - bankCredits).toFixed(2))

  if (error != null) return <div className="p-10"><ErrorState error={error} /></div>
  if (!steps) {
    return (
      <div className="space-y-6 p-10">
        <SkeletonCards cards={1} />
        <Skeleton className="h-80 w-full" />
      </div>
    )
  }

  return (
    <motion.div {...pageTransition} className="flex h-full gap-6 p-8">
      <div className="flex min-w-0 flex-1 flex-col">
        <h1 className="text-lg font-semibold">Sales to bank</h1>
        <p className="mt-1 text-sm" style={{ color: 'var(--ink-muted)' }}>
          Every step is a signed delta, so the bars add up to the bank credits exactly once.
        </p>

        <div className="card mt-5 flex-1 p-5">
          <ResponsiveContainer width="100%" height={340}>
            <BarChart data={plotted} margin={{ top: 28, right: 8, bottom: 8, left: 8 }}>
              <XAxis
                dataKey="label"
                tick={{ fontSize: 10, fill: 'var(--ink-faint)' }}
                tickFormatter={(label: string) => (label.length > 14 ? `${label.slice(0, 13)}…` : label)}
                axisLine={{ stroke: 'var(--line)' }}
                tickLine={false}
                interval={0}
              />
              <Tooltip
                cursor={{ fill: 'var(--line)', opacity: 0.4 }}
                content={({ active, payload }) => {
                  if (!active || !payload?.length) return null
                  const step = payload[0].payload as Plotted
                  return (
                    <div className="card px-3 py-2 text-xs" style={{ boxShadow: 'var(--shadow)' }}>
                      <p className="font-medium">{step.label}</p>
                      <p className="amount mt-1" style={{ textAlign: 'left', color: step.color }}>
                        {signedRupees(step.amount)}
                      </p>
                      <p className="mt-1" style={{ color: 'var(--ink-muted)' }}>
                        {step.sourceRowIds.length} source rows · running {rupees(step.running)}
                      </p>
                    </div>
                  )
                }}
              />
              {/* An invisible base lifts each bar to where the running total left it. */}
              <Bar dataKey="base" stackId="waterfall" fill="transparent" isAnimationActive={false} />
              <Bar
                dataKey="magnitude"
                stackId="waterfall"
                radius={[3, 3, 0, 0]}
                isAnimationActive={!reduced}
                animationDuration={reduced ? 0 : 420}
                animationBegin={0}
                onClick={(entry: unknown) => {
                  const step = entry as Plotted
                  if (step?.sourceRowIds?.length) onOpenRows(step.label, step.sourceRowIds)
                }}
              >
                {plotted.map((step, index) => (
                  <Cell
                    key={step.label}
                    fill={step.color}
                    cursor={step.sourceRowIds.length > 0 ? 'pointer' : 'default'}
                    style={{ animationDelay: reduced ? '0ms' : `${index * BAR_STAGGER_SECONDS * 1000}ms` }}
                  />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>

          <ul className="mt-4 grid gap-x-4 gap-y-1 text-xs" style={{ gridTemplateColumns: 'repeat(3, minmax(0,1fr))' }}>
            {plotted.map((step) => (
              <li key={step.label} className="flex items-center justify-between gap-2">
                <span className="flex min-w-0 items-center gap-1.5" style={{ color: 'var(--ink-muted)' }}>
                  <span className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ background: step.color }} />
                  <span className="truncate">{step.label}</span>
                </span>
                <span className="amount shrink-0" style={{ color: step.color }}>
                  {signedRupees(step.amount)}
                </span>
              </li>
            ))}
          </ul>

          <p className="mt-4 border-t pt-3 text-sm" style={{ borderColor: 'var(--line)' }}>
            {drift === null ? (
              <span style={{ color: 'var(--ink-muted)' }}>Comparing against bank credits…</span>
            ) : drift === 0 ? (
              <span style={{ color: 'var(--received)' }}>Received ✓ reconciles to the rupee</span>
            ) : (
              <span style={{ color: 'var(--lost)' }}>⚠ Off by {rupees(Math.abs(drift))}</span>
            )}
          </p>
        </div>
      </div>

      <motion.aside
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.35, delay: 0.15 }}
        className="card w-[320px] shrink-0 self-start p-5"
      >
        <h2 className="text-sm font-semibold">What happened</h2>
        {narrative === null && narrativeError == null && <Skeleton className="mt-3 h-28 w-full" />}
        {narrative && <NarrativeText text={narrative} onOpenRows={onOpenRows} />}
        {narrativeError != null && (
          <div className="mt-3">
            <p className="text-xs" style={{ color: 'var(--ink-muted)' }}>
              No narration: the numbers above are unaffected.
            </p>
            <button
              type="button"
              onClick={() => onAskAbout('Explain the difference between sales and bank credits.')}
              className="mt-3 text-xs underline"
              style={{ color: 'var(--accent)' }}
            >
              Ask instead
            </button>
          </div>
        )}
      </motion.aside>
    </motion.div>
  )
}

/** Row ids inside the narration become chips, so a claim can be opened and checked. */
function NarrativeText({ text, onOpenRows }: { text: string; onOpenRows: (title: string, rowIds: number[]) => void }) {
  const parts = text.split(/(\brow \d+\b)/gi)
  return (
    <p className="mt-3 text-sm leading-relaxed" style={{ color: 'var(--ink-muted)' }}>
      {parts.map((part, index) => {
        const rowId = /^row (\d+)$/i.exec(part)
        if (!rowId) return <span key={index}>{part}</span>
        return (
          <button
            key={index}
            type="button"
            onClick={() => onOpenRows(part, [Number(rowId[1])])}
            className="ref mx-0.5 rounded px-1"
            style={{ background: 'var(--line)' }}
          >
            {part}
          </button>
        )
      })}
    </p>
  )
}
