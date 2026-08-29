import { motion } from 'framer-motion'
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, api, type AskResponse, type Citation } from '../api/client'
import { rupees, shortDate } from '../lib/format'
import { useTypewriter } from '../lib/motion'
import { Thinking } from './States'

const SUGGESTIONS = [
  { question: "Why was Tuesday's settlement short?", hint: 'names a day' },
  { question: 'How much is held in disputes?', hint: 'held money' },
  { question: 'What happened to order ORD-000042?', hint: 'names an order' },
]

type Turn = { id: number; question: string; answer: AskResponse | null; error: unknown | null }

/**
 * A conversation about one batch. Cited row ids are chips rather than prose, so an answer can always
 * be checked against the rows it came from; a refusal is shown as-is with a hint, never dressed up.
 *
 * <p>Every turn stays on screen. An answer read three questions ago is often the reason for the
 * question being typed now, and throwing it away to make room for the next one is what made this a
 * lookup box rather than somewhere to think.
 */
export function AskPanel({
  batchId,
  open,
  onToggle,
  pendingQuestion,
  onPendingConsumed,
}: {
  batchId: string
  open: boolean
  onToggle: () => void
  pendingQuestion: string | null
  onPendingConsumed: () => void
}) {
  const [question, setQuestion] = useState('')
  const [turns, setTurns] = useState<Turn[]>([])
  const [asking, setAsking] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const nextId = useRef(0)
  // Survives the panel being collapsed, so reopening does not retype every answer at once.
  const revealed = useRef(new Set<number>())

  const scrollToBottom = useCallback(() => {
    const body = bodyRef.current
    if (body) body.scrollTop = body.scrollHeight
  }, [])

  // A new batch is a different subject; carrying the old conversation into it would cite dead rows.
  useEffect(() => {
    setTurns([])
    revealed.current.clear()
  }, [batchId])

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

  useEffect(() => {
    if (open) scrollToBottom()
  }, [open, turns.length, scrollToBottom])

  async function submit(asked: string) {
    const trimmed = asked.trim()
    if (!trimmed || asking) return
    const id = nextId.current++
    setAsking(true)
    setQuestion('')
    setTurns((current) => [...current, { id, question: trimmed, answer: null, error: null }])
    try {
      const answer = await api.ask(batchId, trimmed)
      setTurns((current) => current.map((turn) => (turn.id === id ? { ...turn, answer } : turn)))
    } catch (error) {
      setTurns((current) => current.map((turn) => (turn.id === id ? { ...turn, error } : turn)))
    } finally {
      setAsking(false)
      inputRef.current?.focus()
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
        <div className="flex items-center gap-1">
          {turns.length > 0 && (
            <button
              type="button"
              onClick={() => {
                setTurns([])
                revealed.current.clear()
              }}
              className="rounded px-1.5 py-1 text-xs"
              style={{ color: 'var(--ink-faint)' }}
              title="Clear this conversation"
            >
              Clear
            </button>
          )}
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
        </div>
      </header>

      <div ref={bodyRef} className="flex-1 overflow-y-auto p-4">
        {turns.length === 0 ? (
          <EmptyState onPick={submit} />
        ) : (
          <div className="space-y-5">
            {turns.map((turn) => (
              <div key={turn.id} className="space-y-3">
                <div className="flex justify-end">
                  <p
                    className="max-w-[85%] rounded-2xl rounded-br-sm px-3 py-2 text-sm"
                    style={{ background: 'var(--accent-soft)', color: 'var(--accent)' }}
                  >
                    {turn.question}
                  </p>
                </div>

                {turn.answer === null && turn.error === null && <Thinking label="Reading the rows…" />}

                {turn.answer && (
                  <Answer
                    id={turn.id}
                    answer={turn.answer}
                    revealed={revealed.current}
                    onGrow={scrollToBottom}
                  />
                )}

                {turn.error != null && (
                  <div
                    className="rounded-lg border px-3 py-2"
                    style={{ borderColor: 'var(--lost)', background: 'var(--lost-soft)' }}
                  >
                    <p className="text-sm" style={{ color: 'var(--lost)' }}>
                      {turn.error instanceof Error ? turn.error.message : 'That question could not be answered.'}
                    </p>
                    <p className="mt-1 text-xs" style={{ color: 'var(--ink-muted)' }}>
                      {turn.error instanceof ApiError ? turn.error.hint : 'Try again in a moment.'}
                    </p>
                  </div>
                )}
              </div>
            ))}
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
 * One answer, written out as it is read rather than dropped in whole.
 *
 * <p>The text arrived in full — this only paces it — and each answer types once. Scrolling follows
 * the words down so the end of a long answer is not left below the fold.
 */
function Answer({
  id,
  answer,
  revealed,
  onGrow,
}: {
  id: number
  answer: AskResponse
  revealed: Set<number>
  onGrow: () => void
}) {
  const [animate] = useState(() => !revealed.has(id))
  const { shown, done } = useTypewriter(answer.answer, animate)

  useEffect(() => {
    onGrow()
    if (done) revealed.add(id)
  }, [shown, done, id, revealed, onGrow])

  return (
    <div>
      <p className="text-sm leading-relaxed">
        {shown}
        {!done && <span className="caret" aria-hidden="true" />}
      </p>

      {/* Held back until the answer has finished: a citation is only checkable against a whole claim. */}
      {done &&
        (answer.citations.length > 0 ? (
          <div className="mt-4 border-t pt-3" style={{ borderColor: 'var(--line)' }}>
            <p className="text-xs" style={{ color: 'var(--ink-faint)' }}>
              Answered from {answer.citations.length}{' '}
              {answer.citations.length === 1 ? 'row' : 'rows'}, shown below
            </p>
            <div className="mt-2 space-y-1.5">
              {answer.citations.map((citation) => (
                <CitationChip key={citation.id} citation={citation} />
              ))}
            </div>
          </div>
        ) : (
          <p className="mt-3 text-xs" style={{ color: 'var(--ink-faint)' }}>
            Nothing was cited. Try naming a date, an order id or a UTR.
          </p>
        ))}
    </div>
  )
}

const CITATION_KINDS: Record<Citation['kind'], string> = {
  ORDER: 'Order',
  SETTLEMENT: 'Payout',
  BANK_CREDIT: 'Bank credit',
  EXCEPTION: 'Exception',
}

/**
 * One cited row, named the way the merchant's own records name it.
 *
 * <p>This used to read "row 12542". That number is a database key: it is not on their order export,
 * not on their bank statement, and not anything they can look up. The order id and the UTR are, so
 * those lead — and the amount and date beside them are what make a claim checkable at a glance.
 */
function CitationChip({ citation }: { citation: Citation }) {
  const facts = [
    citation.amount === null ? null : rupees(citation.amount),
    citation.date === null ? null : shortDate(citation.date),
  ].filter(Boolean)

  return (
    <div className="rounded-md border px-2 py-1.5" style={{ borderColor: 'var(--line)' }}>
      <span className="flex items-baseline gap-1.5">
        <span className="text-[10px] uppercase tracking-wider" style={{ color: 'var(--ink-faint)' }}>
          {CITATION_KINDS[citation.kind]}
        </span>
        <span className="ref truncate" style={{ color: 'var(--ink)' }}>
          {citation.ref ?? '—'}
        </span>
      </span>
      {facts.length > 0 && (
        <span className="mt-0.5 block text-[11px]" style={{ color: 'var(--ink-muted)' }}>
          {facts.join(' · ')}
        </span>
      )}
      {citation.note && (
        <span className="mt-0.5 block truncate text-[11px]" style={{ color: 'var(--ink-faint)' }}>
          {citation.note}
        </span>
      )}
    </div>
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
