import { motion } from 'framer-motion'

export type StepState = 'pending' | 'running' | 'done'

/** Ingest → Match → Rules → Exceptions, each filling in as the API gets that far. */
export function StepProgress({ steps }: { steps: { label: string; state: StepState }[] }) {
  return (
    <ol className="flex items-center gap-2">
      {steps.map((step, index) => (
        <li key={step.label} className="flex flex-1 items-center gap-2">
          <div className="flex-1">
            <div className="flex items-center justify-between">
              <span
                className="text-xs font-medium"
                style={{ color: step.state === 'pending' ? 'var(--ink-faint)' : 'var(--ink)' }}
              >
                {step.label}
              </span>
              {step.state === 'done' && (
                <span className="text-xs" style={{ color: 'var(--received)' }}>
                  ✓
                </span>
              )}
            </div>
            <div className="mt-1.5 h-1 w-full overflow-hidden rounded-full" style={{ background: 'var(--line)' }}>
              <motion.div
                className="h-full"
                style={{ background: step.state === 'done' ? 'var(--received)' : 'var(--accent)' }}
                initial={{ width: '0%' }}
                animate={{ width: step.state === 'pending' ? '0%' : step.state === 'running' ? '55%' : '100%' }}
                transition={{ duration: 0.3, ease: 'easeOut' }}
              />
            </div>
          </div>
          {index < steps.length - 1 && <span style={{ color: 'var(--ink-faint)' }}>→</span>}
        </li>
      ))}
    </ol>
  )
}
