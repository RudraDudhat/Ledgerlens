import type { ReactNode } from 'react'
import { ApiError } from '../api/client'

/** An error that does not say what to do next is just a stack trace with better manners. */
export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const message = error instanceof Error ? error.message : 'Something went wrong.'
  const hint = error instanceof ApiError ? error.hint : 'Try again, or check the backend logs.'
  return (
    <div className="card p-6" role="alert">
      <p className="text-sm font-medium" style={{ color: 'var(--lost)' }}>
        {message}
      </p>
      <p className="mt-1 text-sm" style={{ color: 'var(--ink-muted)' }}>
        {hint}
      </p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-4 rounded-lg px-3 py-1.5 text-sm font-medium"
          style={{ background: 'var(--accent-soft)', color: 'var(--accent)' }}
        >
          Try again
        </button>
      )}
    </div>
  )
}

export function EmptyState({ title, body, action }: { title: string; body: string; action?: ReactNode }) {
  return (
    <div className="card flex flex-col items-center justify-center p-12 text-center">
      <p className="text-sm font-medium">{title}</p>
      <p className="mt-1 max-w-sm text-sm" style={{ color: 'var(--ink-muted)' }}>
        {body}
      </p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}
