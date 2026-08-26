import { motion } from 'framer-motion'
import { useEffect, useRef, useState } from 'react'
import { ApiError, api, type AskResponse } from '../api/client'
import { Skeleton } from './Skeleton'

const SUGGESTIONS = [
  "Why was Tuesday's settlement short?",
  'How much is held in disputes?',
  'What happened to order ORD-000042?',
]

type Exchange = { question: string; answer: AskResponse | null; error: unknown | null }

/**
 * One question, one answer. Cited row ids are chips rather than prose, so an answer can always be
 * checked against the rows it came from; a refusal is shown as-is with a hint, never dressed up.
 */
export function AskPanel({
  batchId,
  open,
  onToggle,
  pendingQuestion,
  onPendingConsumed,
  onOpenRows,
}: {
  batchId: string
  open: boolean
  onToggle: () => void
  pendingQuestion: string | null
  onPendingConsumed: () => void
  onOpenRows: (rowIds: number[]) => void
}) {
  const [question, setQuestion] = useState('')
  const [exchange, setExchange] = useState<Exchange | null>(null)
  const [asking, setAsking] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const typingElsewhere =
        event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement
      if (event.key === '/' && !typingElsewhere) {
        event.preventDefault()
        if (!open) onToggle()
        inputRef.current?.focus()
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [open, onToggle])

  useEffect(() => {
    if (!pendingQuestion) return
    setQuestion(pendingQuestion)
    if (!open) onToggle()
    inputRef.current?.focus()
    onPendingConsumed()
  }, [pendingQuestion, open, onToggle, onPendingConsumed])

  async function submit(asked: string) {
    const trimmed = asked.trim()
    if (!trimmed || asking) return
    setAsking(true)
    setExchange({ question: trimmed, answer: null, error: null })
    try {
      const answer = await api.ask(batchId, trimmed)
      setExchange({ question: trimmed, answer, error: null })
    } catch (error) {
      setExchange({ question: trimmed, answer: null, error })
    } finally {
      setAsking(false)
      setQuestion('')
    }
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={onToggle}
        className="fixed right-0 top-1/2 z-30 -translate-y-1/2 rounded-l-lg border border-r-0 px-2 py-4 text-xs"
        style={{ background: 'var(--surface-raised)', borderColor: 'var(--line)', color: 'var(--ink-muted)' }}
      >
        Ask <span className="ref">/</span>
      </button>
    )
  }

  return (
    <motion.section
      initial={{ x: 40, opacity: 0 }}
      animate={{ x: 0, opacity: 1 }}
      transition={{ duration: 0.15, ease: 'easeOut' }}
      className="flex w-[340px] shrink-0 flex-col border-l"
      style={{ background: 'var(--surface-raised)', borderColor: 'var(--line)' }}
      aria-label="Ask"
    >
      <header className="flex items-center justify-between border-b px-4 py-3" style={{ borderColor: 'var(--line)' }}>
        <h2 className="text-sm font-semibold">Ask</h2>
        <button type="button" onClick={onToggle} className="text-sm" style={{ color: 'var(--ink-faint)' }}>
          ✕
        </button>
      </header>

      <div className="flex-1 overflow-y-auto p-4">
        {!exchange && (
          <div>
            <p className="text-xs" style={{ color: 'var(--ink-muted)' }}>
              Questions are answered only from rows in this batch. Mention a date, an order id or a UTR.
            </p>
            <div className="mt-3 space-y-2">
              {SUGGESTIONS.map((suggestion) => (
                <button
                  key={suggestion}
                  type="button"
                  onClick={() => submit(suggestion)}
                  className="w-full rounded-lg border px-3 py-2 text-left text-xs"
                  style={{ borderColor: 'var(--line)', color: 'var(--ink-muted)' }}
                >
                  {suggestion}
                </button>
              ))}
            </div>
          </div>
        )}

        {exchange && (
          <div className="space-y-3">
            <p className="rounded-lg px-3 py-2 text-sm" style={{ background: 'var(--accent-soft)', color: 'var(--accent)' }}>
              {exchange.question}
            </p>
            {asking && <Skeleton className="h-16 w-full" />}
            {exchange.answer && (
              <div>
                <p className="text-sm leading-relaxed">{exchange.answer.answer}</p>
                {exchange.answer.citedRowIds.length > 0 ? (
                  <div className="mt-3 flex flex-wrap gap-1">
                    {exchange.answer.citedRowIds.map((id) => (
                      <button
                        key={id}
                        type="button"
                        onClick={() => onOpenRows([id])}
                        className="ref rounded px-1.5 py-0.5"
                        style={{ background: 'var(--line)' }}
                      >
                        row {id}
                      </button>
                    ))}
                  </div>
                ) : (
                  <p className="mt-2 text-xs" style={{ color: 'var(--ink-faint)' }}>
                    Nothing was cited. Try including a date, an order id or a UTR.
                  </p>
                )}
              </div>
            )}
            {exchange.error != null && (
              <div>
                <p className="text-sm" style={{ color: 'var(--lost)' }}>
                  {exchange.error instanceof Error ? exchange.error.message : 'That question could not be answered.'}
                </p>
                <p className="mt-1 text-xs" style={{ color: 'var(--ink-muted)' }}>
                  {exchange.error instanceof ApiError ? exchange.error.hint : 'Try again in a moment.'}
                </p>
              </div>
            )}
          </div>
        )}
      </div>

      <form
        className="border-t p-3"
        style={{ borderColor: 'var(--line)' }}
        onSubmit={(event) => {
          event.preventDefault()
          void submit(question)
        }}
      >
        <input
          ref={inputRef}
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="Ask about this batch"
          className="w-full rounded-lg border px-3 py-2 text-sm outline-none"
          style={{ background: 'var(--surface)', borderColor: 'var(--line)', color: 'var(--ink)' }}
        />
      </form>
    </motion.section>
  )
}
