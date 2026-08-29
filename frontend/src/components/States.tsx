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

/**
 * Shown while the model is writing.
 *
 * <p>A grey block is a promise about shape: it says something roughly this tall and this wide is
 * about to appear here. Nothing about a paragraph of prose is known before it arrives, so the block
 * was guessing, and a wrong guess that then reflows is worse than no guess. A pulse claims only what
 * is true — work is happening somewhere else, and it has not stopped.
 */
export function Thinking({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-2.5" role="status">
      <svg
        viewBox="0 0 16 16"
        className="thinking-glyph h-3.5 w-3.5 shrink-0"
        fill="var(--accent)"
        aria-hidden="true"
      >
        <path d="M8 0 L9.5 6.5 L16 8 L9.5 9.5 L8 16 L6.5 9.5 L0 8 L6.5 6.5 Z" />
      </svg>
      <span className="text-sm" style={{ color: 'var(--ink-muted)' }}>
        {label}
      </span>
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
