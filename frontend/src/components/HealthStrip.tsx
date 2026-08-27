import { motion } from 'framer-motion'
import { useEffect, useState } from 'react'
import { healthApi, type AlertView, type BatchMetrics, type HealthReport, type HealthHistoryPoint } from '../api/client'
import { Skeleton } from './Skeleton'
import { usePrefersReducedMotion } from '../lib/motion'

/** Each card names the metric the detector uses, so a card and its alert are obviously the same thing. */
const CARDS: { metric: string; label: string; read: (m: BatchMetrics) => number; format: (v: number) => string }[] = [
  { metric: 'fee_rate', label: 'Fee rate', read: (m) => m.feeRate, format: percent },
  { metric: 'failure_rate', label: 'Failure rate', read: (m) => m.failureRate, format: percent },
  { metric: 'dispute_rate', label: 'Dispute rate', read: (m) => m.disputeRate, format: percent },
  {
    metric: 'avg_settlement_delay_days',
    label: 'Settlement delay',
    read: (m) => m.avgSettlementDelayDays,
    format: (v) => `${v.toFixed(1)} d`,
  },
  { metric: 'match_rate', label: 'Match rate', read: (m) => m.matchRate, format: percent },
]

function percent(value: number): string {
  return `${(value * 100).toFixed(2)}%`
}

/**
 * Five vital signs above the table, each against its own history.
 *
 * <p>An alerted card is marked by colour, a border and a word, never by colour alone. Until there
 * are two earlier batches the strip says so plainly rather than drawing a baseline out of one
 * number and pretending it means something.
 */
export function HealthStrip({
  batchId,
  onOpenAlert,
}: {
  batchId: string
  onOpenAlert: (alert: AlertView, failureRateByHour: Record<string, number>) => void
}) {
  const [report, setReport] = useState<HealthReport | null>(null)
  const [history, setHistory] = useState<HealthHistoryPoint[]>([])
  const [failed, setFailed] = useState(false)
  const reduced = usePrefersReducedMotion()

  useEffect(() => {
    let cancelled = false
    setReport(null)
    setFailed(false)
    Promise.all([healthApi.report(batchId), healthApi.history(batchId)])
      .then(([loaded, points]) => {
        if (cancelled) return
        setReport(loaded)
        setHistory(points)
      })
      .catch(() => !cancelled && setFailed(true))
    return () => {
      cancelled = true
    }
  }, [batchId])

  // Health is a companion to the table, never a blocker: a failure here hides the strip and no more.
  if (failed) return null
  if (!report) return <Skeleton className="h-24 w-full" />

  const alertsByMetric = new Map(report.alerts.map((alert) => [alert.metric, alert]))
  const hourAlerts = report.alerts.filter((alert) => alert.metric.startsWith('failure_rate_hour_'))

  return (
    <div>
      <div className="grid grid-cols-5 gap-3">
        {CARDS.map((card, index) => {
          const value = card.read(report.metrics)
          const baseline = report.baseline ? card.read(report.baseline) : null
          // An hour-bucket alert is a failure story, so it marks the failure card.
          const alert = alertsByMetric.get(card.metric) ?? (card.metric === 'failure_rate' ? hourAlerts[0] : undefined)
          return (
            <HealthCard
              key={card.metric}
              label={card.label}
              value={card.format(value)}
              delta={baseline === null ? null : deltaLabel(value, baseline, card.format)}
              points={history.map((point) => card.read(point.metrics))}
              alert={alert}
              index={index}
              reduced={reduced}
              onOpen={() => alert && onOpenAlert(alert, report.metrics.failureRateByHour)}
            />
          )
        })}
      </div>

      {report.insufficientHistory && (
        <p className="mt-2 text-xs" style={{ color: 'var(--ink-faint)' }}>
          Need {Math.max(0, 2 - report.priorBatchCount)} more{' '}
          {2 - report.priorBatchCount === 1 ? 'batch' : 'batches'} for baselines — nothing is compared yet.
        </p>
      )}
    </div>
  )
}

function deltaLabel(value: number, baseline: number, format: (v: number) => string): string {
  const difference = value - baseline
  if (Math.abs(difference) < 1e-9) return `same as ${format(baseline)}`
  return `${difference > 0 ? '+' : '−'}${format(Math.abs(difference))} vs ${format(baseline)}`
}

function HealthCard({
  label,
  value,
  delta,
  points,
  alert,
  index,
  reduced,
  onOpen,
}: {
  label: string
  value: string
  delta: string | null
  points: number[]
  alert: AlertView | undefined
  index: number
  reduced: boolean
  onOpen: () => void
}) {
  const tone = alert ? (alert.severity === 'HIGH' ? 'var(--lost)' : 'var(--held)') : 'var(--line)'

  return (
    <motion.button
      type="button"
      disabled={!alert}
      onClick={onOpen}
      initial={reduced ? false : { opacity: 0, y: 4 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.15, delay: reduced ? 0 : index * 0.04 }}
      className="card px-3 py-2.5 text-left disabled:cursor-default"
      style={{ borderColor: tone, borderWidth: alert ? 1.5 : 1 }}
      title={alert ? `${alert.metric} is ${alert.ratio.toFixed(1)}× its baseline` : undefined}
    >
      <div className="flex items-baseline justify-between">
        <span className="text-[11px] uppercase tracking-wider" style={{ color: 'var(--ink-faint)' }}>
          {label}
        </span>
        {alert && (
          <span className="text-[10px] font-medium uppercase" style={{ color: tone }}>
            {alert.severity}
          </span>
        )}
      </div>

      <p className="amount mt-1 text-lg" style={{ textAlign: 'left' }}>
        {value}
      </p>

      <Sparkline points={points} tone={alert ? tone : 'var(--ink-muted)'} reduced={reduced} />

      <p className="mt-1 truncate text-[11px]" style={{ color: 'var(--ink-faint)' }}>
        {alert ? `${alert.ratio.toFixed(1)}× baseline` : (delta ?? 'no baseline yet')}
      </p>
    </motion.button>
  )
}

const TRACK_PX = 24
const FLOOR_PX = 7

/**
 * Eight batches at most, one bar each, the last being this batch. Shape only — the number above it
 * carries the value.
 *
 * <p>Bars rather than a line because at 24px a 1.5px stroke is barely a mark, and because a flat
 * history has to look like a deliberate answer. A run of identical readings draws a level row at
 * mid-height; the earlier drawing put it hard against the bottom edge, where it read as a stray rule
 * rather than as "steady". Every bar keeps a floor, so the lowest batch is still a bar.
 */
function Sparkline({ points, tone, reduced }: { points: number[]; tone: string; reduced: boolean }) {
  const recent = points.slice(-8)
  if (recent.length < 2) {
    return <div className="mt-1.5 h-6" aria-hidden="true" />
  }

  const highest = Math.max(...recent)
  const lowest = Math.min(...recent)
  const span = highest - lowest

  return (
    <div className="mt-1.5 flex h-6 items-end gap-[3px]" aria-hidden="true">
      {recent.map((value, index) => {
        const height =
          span === 0 ? TRACK_PX * 0.55 : FLOOR_PX + ((value - lowest) / span) * (TRACK_PX - FLOOR_PX)
        // This batch is the one the card is about; the rest are context behind it.
        const current = index === recent.length - 1
        return (
          <motion.span
            key={index}
            className="block min-w-0 flex-1 rounded-[1.5px]"
            style={{ background: tone, opacity: current ? 1 : 0.4 }}
            initial={reduced ? false : { height: 0 }}
            animate={{ height }}
            transition={{ duration: 0.3, ease: 'easeOut', delay: reduced ? 0 : index * 0.03 }}
          />
        )
      })}
    </div>
  )
}
