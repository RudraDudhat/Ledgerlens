import { motion } from 'framer-motion'
import { useEffect, useRef, useState } from 'react'
import { ApiError, api, type AskResponse } from '../api/client'
import { Skeleton } from './Skeleton'

const SUGGESTIONS = [
  { question: "Why was Tuesday's settlement short?", hint: 'names a day' },
  { question: 'How much is held in disputes?', hint: 'held money' },
  { question: 'What happened to order ORD-000042?', hint: 'names an order' },
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
      // Esc leaves the field first, so one press stops typing and a second closes the panel.
      if (event.key === 'Escape' && open) {
        if (document.activeElement === inputRef.current) inputRef.current?.blur()
        else onToggle()
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
    return <CollapsedTab onOpen={onToggle} />
  }

  return (
    <motion.section
      initial={{ x: 40, opacity: 0 }}
      animate={{ x: 0, opacity: 1 }}
      transition={{ duration: 0.15, ease: 'easeOut' }}
      className="flex w-[360px] shrink-0 flex-col border-l"
      style={{ background: 'var(--surface-raised)', borderColor: 'var(--line)' }}
      aria-label="Ask"
    >
      <header
        className="flex items-start justify-between border-b px-4 py-3"
        style={{ borderColor: 'var(--line)' }}
      >
        <div>
          <h2 className="text-sm font-semibold">Ask</h2>
          <p className="mt-0.5 text-xs" style={{ color: 'var(--ink-faint)' }}>
            Answered only from rows in this batch
          </p>
        </div>
        <button
          type="button"
          onClick={onToggle}
          className="rounded p-1 text-sm leading-none"
          style={{ color: 'var(--ink-faint)' }}
          aria-label="Close (Esc)"
          title="Close (Esc)"
        >
          ✕
        </button>
      </header>

      <div className="flex-1 overflow-y-auto p-4">
        {!exchange && <EmptyState onPick={submit} />}

        {exchange && (
          <div className="space-y-4">
            <div className="flex justify-end">
              <p
                className="max-w-[85%] rounded-2xl rounded-br-sm px-3 py-2 text-sm"
                style={{ background: 'var(--accent-soft)', color: 'var(--accent)' }}
              >
                {exchange.question}
              </p>
            </div>

            {asking && (
              <div>
                <p className="mb-2 text-xs" style={{ color: 'var(--ink-faint)' }}>
                  Reading the rows…
                </p>
                <Skeleton className="h-20 w-full" />
              </div>
            )}

            {exchange.answer && (
              <div>
                <p className="text-sm leading-relaxed">{exchange.answer.answer}</p>

                {exchange.answer.citedRowIds.length > 0 ? (
                  <div className="mt-4 border-t pt-3" style={{ borderColor: 'var(--line)' }}>
                    <p className="text-xs" style={{ color: 'var(--ink-faint)' }}>
                      Answered from {exchange.answer.citedRowIds.length}{' '}
                      {exchange.answer.citedRowIds.length === 1 ? 'row' : 'rows'} — open one to check it
                    </p>
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {exchange.answer.citedRowIds.map((id) => (
                        <button
                          key={id}
                          type="button"
                          onClick={() => onOpenRows([id])}
                          className="ref rounded-md border px-2 py-1 transition-colors"
                          style={{ borderColor: 'var(--line)', color: 'var(--ink-muted)' }}
                        >
                          row {id}
                        </button>
                      ))}
                    </div>
                  </div>
                ) : (
                  <p className="mt-3 text-xs" style={{ color: 'var(--ink-faint)' }}>
                    Nothing was cited. Try naming a date, an order id or a UTR.
                  </p>
                )}
              </div>
            )}

            {exchange.error != null && (
              <div
                className="rounded-lg border px-3 py-2"
                style={{ borderColor: 'var(--lost)', background: 'var(--lost-soft)' }}
              >
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
        <div
          className="flex items-center gap-2 rounded-lg border px-3 py-2"
          style={{ background: 'var(--surface)', borderColor: 'var(--line)' }}
        >
          <input
            ref={inputRef}
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            disabled={asking}
            placeholder={asking ? 'Answering…' : 'Ask about this batch'}
            className="min-w-0 flex-1 bg-transparent text-sm outline-none disabled:cursor-not-allowed"
            style={{ color: 'var(--ink)' }}
          />
          <button
            type="submit"
            disabled={asking || question.trim().length === 0}
            className="shrink-0 rounded-md px-2 py-1 text-xs font-medium transition-opacity disabled:opacity-40"
            style={{ background: 'var(--accent)', color: '#fff' }}
            aria-label="Send question"
          >
            Ask
          </button>
        </div>
      </form>
    </motion.section>
  )
}

/**
 * The collapsed handle. It used to be muted text on a muted panel at the very edge, which read as a
 * rendering artefact rather than a control. It is now an accent tab stacked into a single narrow
 * column — label above the key hint — so it stays slim enough to sit in its own gutter instead of
 * covering the content behind it.
 */
function CollapsedTab({ onOpen }: { onOpen: () => void }) {
  return (
    <button
      type="button"
      onClick={onOpen}
      title="Ask about this batch (press /)"
      aria-label="Open the Ask panel"
      className="fixed right-0 top-1/2 z-30 flex -translate-y-1/2 flex-col items-center gap-2.5 rounded-l-lg py-5 pl-2 pr-1.5"
      style={{ background: 'var(--accent)', color: '#fff', boxShadow: 'var(--shadow)' }}
    >
      <span className="text-sm font-medium tracking-wide [writing-mode:vertical-rl]">Ask</span>
      <kbd
        className="ref flex h-4 w-4 items-center justify-center rounded text-[10px] leading-none"
        style={{ background: 'rgb(255 255 255 / 22%)', color: '#fff' }}
      >
        /
      </kbd>
    </button>
  )
}

function EmptyState({ onPick }: { onPick: (question: string) => void }) {
  return (
    <div>
      <p className="text-xs leading-relaxed" style={{ color: 'var(--ink-muted)' }}>
        Every answer is built from rows that were actually retrieved. Name a date, an order id or a
        UTR and it will find them; if it finds nothing it says so rather than guessing.
      </p>

      <p className="mt-5 text-xs font-medium uppercase tracking-wider" style={{ color: 'var(--ink-faint)' }}>
        Try one of these
      </p>
      <div className="mt-2 space-y-2">
        {SUGGESTIONS.map((suggestion) => (
          <button
            key={suggestion.question}
            type="button"
            onClick={() => onPick(suggestion.question)}
            className="group flex w-full items-start justify-between gap-2 rounded-lg border px-3 py-2.5 text-left"
            style={{ borderColor: 'var(--line)' }}
          >
            <span className="text-xs leading-snug" style={{ color: 'var(--ink)' }}>
              {suggestion.question}
              <span className="mt-0.5 block text-[11px]" style={{ color: 'var(--ink-faint)' }}>
                {suggestion.hint}
              </span>
            </span>
            <span className="mt-0.5 shrink-0 text-xs" style={{ color: 'var(--accent)' }}>
              →
            </span>
          </button>
        ))}
      </div>
    </div>
  )
}
