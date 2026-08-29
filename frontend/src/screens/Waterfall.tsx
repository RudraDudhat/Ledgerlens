import { motion } from 'framer-motion'
import { useEffect, useMemo, useState } from 'react'
import { Bar, BarChart, Cell, ResponsiveContainer, Tooltip, XAxis } from 'recharts'
import { ApiError, api, type WaterfallStep } from '../api/client'
import { Skeleton, SkeletonCards } from '../components/Skeleton'
import { ErrorState, Thinking } from '../components/States'
import { rupees, signedRupees } from '../lib/format'
import { BAR_STAGGER_SECONDS, pageTransition, usePrefersReducedMotion, useTypewriter } from '../lib/motion'
import {
  hasRevealedNarrative,
  markNarrativeRevealed,
  pendingNarrative,
  retryNarrative,
} from '../lib/narrative'

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

/**
 * Short names under the bars. Ten full labels do not fit across a chart, and cutting them mid-word
 * produced "Settlements n…" and "Unmatched ban…", which name nothing. The legend directly below
 * carries every label in full, so the axis only has to tell one bar from its neighbour.
 */
const AXIS_LABEL: Record<string, string> = {
  'Gross sales': 'Sales',
  'Failed payments': 'Failed',
  'Razorpay fees': 'Fees',
  'GST on fees': 'GST',
  'Held for disputes': 'Disputes',
  Refunds: 'Refunds',
  'Settlements not credited by bank': 'Not credited',
  'Unmatched bank credits': 'Unmatched',
  'Bank amount differences': 'Differences',
  'Unexplained settlement difference': 'Unexplained',
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

    // Read-only: whatever Reconcile started. Opening this screen never begins a model call.
    const started = pendingNarrative(batchId)
    if (started) {
      started.then((text) => !cancelled && setNarrative(text)).catch((error) => !cancelled && setNarrativeError(error))
    } else {
      setNarrativeError(new Error('No explanation was generated for this batch.'))
    }

    Promise.all([api.waterfall(batchId), api.summary(batchId)])
      .then(([loadedSteps, summary]) => {
        if (cancelled) return
        setSteps(loadedSteps)
        setBankCredits(summary.totalBankCredits)
      })
      .catch((loadError) => !cancelled && setError(loadError))


    return () => {
      cancelled = true
    }
  }, [batchId])


  /** The only path that spends a second call, and only because someone asked for it. */
  function retry() {
    setNarrative(null)
    setNarrativeError(null)
    retryNarrative(batchId)
      .then(setNarrative)
      .catch(setNarrativeError)
  }

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
    <motion.div {...pageTransition} className="no-scrollbar flex h-full flex-col gap-6 overflow-y-auto p-8">
      <div className="min-w-0">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <h1 className="text-lg font-semibold">Sales to bank</h1>
            {/* "Every step is a signed delta" was true and unreadable. The reader is a founder, not
                the person who wrote the reconciler. */}
            <p className="mt-1 text-sm" style={{ color: 'var(--ink-muted)' }}>
              Each bar is one thing that happened to your money on the way to the bank. Together they
              account for the whole difference, once each.
            </p>
          </div>
          <StatementButton batchId={batchId} ready={narrative !== null} />
        </div>

        <div className="card mt-5 p-5">
          <ResponsiveContainer width="100%" height={340}>
            <BarChart data={plotted} margin={{ top: 28, right: 8, bottom: 8, left: 8 }}>
              <XAxis
                dataKey="label"
                tick={{ fontSize: 10, fill: 'var(--ink-faint)' }}
                tickFormatter={(label: string) => AXIS_LABEL[label] ?? label}
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
                      {/* "running" alone left the reader to work out running what. This is the
                          number they care about: where the money stands after this bar. */}
                      <p className="mt-1" style={{ color: 'var(--ink-muted)' }}>
                        {step.sourceRowIds.length} {step.sourceRowIds.length === 1 ? 'row' : 'rows'} ·
                        leaves {rupees(step.running)}
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
                  <span className="truncate" title={step.label}>{step.label}</span>
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

      <motion.section
        initial={{ opacity: 0 }}
        animate={{ opacity: 1 }}
        transition={{ duration: 0.35, delay: 0.15 }}
        className="card p-5"
      >
        <h2 className="text-sm font-semibold">What happened</h2>

        {narrative === null && narrativeError == null && (
          <div className="mt-3">
            <Thinking label="Writing the explanation…" />
          </div>
        )}

        {narrative !== null && (
          <NarrativeText batchId={batchId} text={narrative} onOpenRows={onOpenRows} />
        )}

        {narrativeError != null && (
          <div className="mt-3">
            <p className="max-w-[70ch] text-xs" style={{ color: 'var(--ink-muted)' }}>
              No narration: the numbers above are unaffected.
            </p>
            <div className="mt-3 flex gap-3">
              <button type="button" onClick={retry} className="text-xs underline" style={{ color: 'var(--accent)' }}>
                Try again
              </button>
              <button
                type="button"
                onClick={() => onAskAbout('Explain the difference between sales and bank credits.')}
                className="text-xs underline"
                style={{ color: 'var(--accent)' }}
              >
                Ask instead
              </button>
            </div>
          </div>
        )}
      </motion.section>
    </motion.div>
  )
}

/**
 * Downloads the statement the backend renders.
 *
 * <p>Held back until the narration has landed, because the statement quotes it: offering the
 * download earlier would hand the founder a PDF missing the one section written in their language.
 */
function StatementButton({ batchId, ready }: { batchId: string; ready: boolean }) {
  const [downloading, setDownloading] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function download() {
    if (downloading) return
    setDownloading(true)
    setError(null)
    try {
      const { blob, filename } = await api.statementPdf(batchId)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = filename
      link.click()
      // The browser has the bytes by now; holding the object URL open only leaks it.
      URL.revokeObjectURL(url)
    } catch (downloadError) {
      setError(downloadError)
    } finally {
      setDownloading(false)
    }
  }

  return (
    <div className="shrink-0 text-right">
      <button
        type="button"
        onClick={download}
        disabled={!ready || downloading}
        title={ready ? 'A one-page PDF for you and your accountant' : 'Available once the explanation has been written'}
        className="rounded-md border px-3 py-1.5 text-xs font-medium transition-opacity disabled:cursor-not-allowed disabled:opacity-40"
        style={{ background: 'var(--accent)', borderColor: 'var(--accent)', color: '#fff' }}
      >
        {downloading ? 'Preparing…' : 'Download statement (PDF)'}
      </button>
      {error != null && (
        <p className="mt-1.5 text-[11px]" style={{ color: 'var(--lost)' }}>
          {error instanceof ApiError ? error.hint : 'That download failed. Try again.'}
        </p>
      )}
    </div>
  )
}

/** A row id to open, or an amount to re-cut into the grouping the rest of the screen uses. */
const NARRATION_TOKENS = /(\brow \d+\b|₹[\d,]+(?:\.\d+)?)/gi

/**
 * Row ids inside the narration become chips, so a claim can be opened and checked. Amounts are
 * regrouped and set in the same monospace as the legend above: the model writes ₹1554691.47, which
 * cannot be compared to a legend reading ₹15,54,691.47 without counting digits.
 *
 * <p>The text is paced out word by word. It has already arrived in full — this only shows it being
 * written, and a chip or an amount forms as soon as the word carrying it lands.
 */
function NarrativeText({
  batchId,
  text,
  onOpenRows,
}: {
  batchId: string
  text: string
  onOpenRows: (title: string, rowIds: number[]) => void
}) {
  // Decided once, at mount: whether this batch has been read before does not change while reading it.
  const [animate] = useState(() => !hasRevealedNarrative(batchId))
  const { shown, done } = useTypewriter(text, animate)

  useEffect(() => {
    if (done) markNarrativeRevealed(batchId)
  }, [done, batchId])
  const parts = shown.split(NARRATION_TOKENS)
  return (
    <p className="mt-3 max-w-[68ch] text-sm leading-7" style={{ color: 'var(--ink-muted)' }}>
      {parts.map((part, index) => {
        const rowId = /^row (\d+)$/i.exec(part)
        if (rowId) {
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
        }
        // Brighter than the prose around it, so the eye can pick the numbers out of the sentence.
        if (part.startsWith('₹')) {
          return (
            <span key={index} className="amount" style={{ color: 'var(--ink)' }}>
              {rupees(Number(part.slice(1).replace(/,/g, '')))}
            </span>
          )
        }
        return <span key={index}>{part}</span>
      })}
      {!done && <span className="caret" aria-hidden="true" />}
    </p>
  )
}
