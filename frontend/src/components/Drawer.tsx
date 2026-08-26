import { AnimatePresence, motion } from 'framer-motion'
import { useEffect } from 'react'
import type { ExceptionView, MatchView } from '../api/client'
import { rupees, shortDate } from '../lib/format'
import { drawerTransition } from '../lib/motion'
import { StatusBadge } from './StatusBadge'

export type DrawerSubject =
  | { kind: 'row'; orderId: string; match?: MatchView; exception?: ExceptionView }
  | { kind: 'rows'; title: string; rowIds: number[] }

/**
 * The evidence trail, drawn as a vertical stepper: order → payment → settlement line → bank credit.
 * A link that does not exist is drawn as an explicit gap carrying the reason it is missing, because
 * "nothing here" is the most important thing a reconciliation can tell you.
 */
export function Drawer({
  subject,
  onClose,
  onAskAbout,
}: {
  subject: DrawerSubject | null
  onClose: () => void
  onAskAbout?: (question: string) => void
}) {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  return (
    <AnimatePresence>
      {subject && (
        <motion.aside
          key="drawer"
          {...drawerTransition}
          className="fixed right-0 top-0 z-40 h-full w-[420px] overflow-y-auto border-l p-6"
          style={{ background: 'var(--surface-raised)', borderColor: 'var(--line)', boxShadow: 'var(--shadow)' }}
          role="dialog"
          aria-label="Evidence trail"
        >
          <div className="flex items-start justify-between">
            <h2 className="text-sm font-semibold">
              {subject.kind === 'row' ? subject.orderId : subject.title}
            </h2>
            <button
              type="button"
              onClick={onClose}
              className="rounded px-2 text-sm"
              style={{ color: 'var(--ink-faint)' }}
              aria-label="Close (Esc)"
            >
              ✕
            </button>
          </div>

          {subject.kind === 'rows' ? (
            <div className="mt-6">
              <p className="text-xs" style={{ color: 'var(--ink-muted)' }}>
                {subject.rowIds.length} source rows contributed to this step.
              </p>
              <ul className="mt-3 grid grid-cols-4 gap-1">
                {subject.rowIds.slice(0, 200).map((id) => (
                  <li key={id} className="ref rounded px-1.5 py-1 text-center" style={{ background: 'var(--line)' }}>
                    {id}
                  </li>
                ))}
              </ul>
              {subject.rowIds.length > 200 && (
                <p className="mt-2 text-xs" style={{ color: 'var(--ink-faint)' }}>
                  showing the first 200 of {subject.rowIds.length}
                </p>
              )}
            </div>
          ) : (
            <EvidenceTrail subject={subject} onAskAbout={onAskAbout} />
          )}
        </motion.aside>
      )}
    </AnimatePresence>
  )
}

function EvidenceTrail({
  subject,
  onAskAbout,
}: {
  subject: Extract<DrawerSubject, { kind: 'row' }>
  onAskAbout?: (question: string) => void
}) {
  const { match, exception, orderId } = subject
  const amount = match?.amount ?? exception?.amount ?? null

  const steps: { label: string; detail: string; present: boolean }[] = [
    { label: 'Order', detail: `${orderId} · ${rupees(amount)}`, present: true },
    {
      label: 'Payment',
      detail: match?.method ?? exception?.method ?? 'no captured payment',
      present: Boolean(match?.method ?? exception?.method),
    },
    {
      label: 'Settlement line',
      detail: match?.utr ? `${match.utr} · settled ${shortDate(match.settledOn)}` : 'never reached a settlement',
      present: Boolean(match?.utr),
    },
    {
      label: 'Bank credit',
      detail: match?.bankDate ? `${shortDate(match.bankDate)} · ${rupees(match.bankAmount)}` : 'no bank credit carries it',
      present: Boolean(match?.bankDate),
    },
  ]

  return (
    <div className="mt-6">
      {exception && (
        <div className="mb-5">
          <StatusBadge status={exception.status} />
          <p className="mt-2 text-sm" style={{ color: 'var(--ink-muted)' }}>
            {exception.reason}
          </p>
          <p className="ref mt-2" style={{ color: 'var(--ink-faint)' }}>
            confidence {exception.confidence.toFixed(3)} · decided by {exception.origin.toLowerCase()}
          </p>
        </div>
      )}

      <ol className="relative border-l pl-5" style={{ borderColor: 'var(--line)' }}>
        {steps.map((step) => (
          <li key={step.label} className="relative pb-5 last:pb-0">
            <span
              className="absolute -left-[26px] top-1 flex h-3 w-3 items-center justify-center rounded-full"
              style={{
                background: step.present ? 'var(--received)' : 'var(--surface-raised)',
                border: step.present ? 'none' : '1.5px dashed var(--lost)',
              }}
            />
            <p className="text-xs font-medium">{step.label}</p>
            <p
              className={step.present ? 'ref mt-0.5' : 'mt-0.5 text-xs italic'}
              style={{ color: step.present ? 'var(--ink-muted)' : 'var(--lost)' }}
            >
              {step.detail}
            </p>
          </li>
        ))}
      </ol>

      {onAskAbout && (
        <button
          type="button"
          onClick={() => onAskAbout(`What happened to order ${orderId}?`)}
          className="mt-6 w-full rounded-lg px-3 py-2 text-sm font-medium"
          style={{ background: 'var(--accent-soft)', color: 'var(--accent)' }}
        >
          Ask about this row
        </button>
      )}
    </div>
  )
}
