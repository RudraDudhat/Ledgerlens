import { motion } from 'framer-motion'
import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, api, type AskResponse, type Citation } from '../api/client'
import { Logo } from './Logo'
import { rupees, shortDate } from '../lib/format'
import { useTypewriter } from '../lib/motion'
import { Thinking } from './States'

/**
 * Openers, no longer labelled with the kind of question they are. Which branch of the router each
 * one takes is an implementation detail, and printing it beside the question explained the machine
 * rather than helping the person.
 */
const SUGGESTIONS = [
  'How much is held in disputes?',
  "Summarize this batch's problems",
  'Which records look suspicious?',
  'What happened to order ORD-000042?',
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
  const inputRef = useRef<HTMLTextAreaElement>(null)
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

  // Height follows the content instead of a fixed row count, so the box shows what was typed into it.
  useEffect(() => {
    const field = inputRef.current
    if (!field) return
    field.style.height = 'auto'
    field.style.height = `${field.scrollHeight}px`
  }, [question])

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
          /*
           * One block per exchange, separated by space rather than a rule: the question sits right,
           * the answer sits left under the mark, and the eye can find where one turn ends without a
           * divider drawn between every pair.
           */
          <div className="space-y-7">
            {turns.map((turn) => (
              <div key={turn.id}>
                <div className="flex justify-end">
                  <p
                    className="max-w-[88%] rounded-2xl rounded-br-md px-3.5 py-2 text-[13px] leading-relaxed"
                    style={{ background: 'var(--accent-soft)', color: 'var(--accent)' }}
                  >
                    {turn.question}
                  </p>
                </div>

                <div className="mt-4 flex gap-2.5">
                  {/* The mark anchors every reply to the same left edge, so a scan down the panel
                      reads as a conversation rather than as alternating loose paragraphs. */}
                  <span className="mt-0.5 shrink-0"><Logo size={18} /></span>

                  <div className="min-w-0 flex-1">
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
                        <p className="text-[13px]" style={{ color: 'var(--lost)' }}>
                          {turn.error instanceof Error ? turn.error.message : 'That question could not be answered.'}
                        </p>
                        <p className="mt-1 text-xs" style={{ color: 'var(--ink-muted)' }}>
                          {turn.error instanceof ApiError ? turn.error.hint : 'Try again in a moment.'}
                        </p>
                      </div>
                    )}
                  </div>
                </div>
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
          className="flex items-end gap-2 rounded-lg border px-3 py-2"
          style={{ background: 'var(--surface)', borderColor: 'var(--line)' }}
        >
          {/*
            A textarea, not an input: a long question used to run off the right edge with its own
            beginning scrolled out of sight, so you could not read back what you had typed. This
            wraps and grows instead, up to a few lines, then scrolls within itself.
          */}
          <textarea
            ref={inputRef}
            rows={1}
            value={question}
            onChange={(event) => setQuestion(event.target.value)}
            onKeyDown={(event) => {
              // Enter sends, Shift+Enter breaks the line — the convention every chat box uses.
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault()
                void submit(question)
              }
            }}
            disabled={asking}
            placeholder={asking ? 'Answering…' : 'Ask about this batch'}
            className="min-w-0 flex-1 resize-none bg-transparent text-sm leading-relaxed outline-none disabled:cursor-not-allowed"
            style={{ color: 'var(--ink)', maxHeight: 120 }}
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
      {/* Answers are read, not skimmed: full ink, generous leading, and a measure narrow enough
          that the eye finds the next line without hunting for it. */}
      <p className="text-[13.5px] leading-[1.75]" style={{ color: 'var(--ink)' }}>
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
            <div className="mt-2 space-y-1">
              {answer.citations.map((citation) => (
                <CitationChip key={citation.id} citation={citation} />
              ))}
            </div>
          </div>
        ) : answer.answerKind === 'CONCEPTUAL' ? (
          /*
           * A definition has no rows behind it and is not supposed to. Telling the reader to name a
           * UTR after they asked what a UTR is reads as a failure when the answer was correct.
           */
          <p className="mt-3 text-xs" style={{ color: 'var(--ink-faint)' }}>
            A definition, not a reading of your data. Name an order id, a UTR or a date to ask about
            this batch.
          </p>
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
  MATCH: 'Order',
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

/**
 * The opening screen.
 *
 * <p>It used to be four full-width cards, each carrying a question and a label describing what kind
 * of question it was. That filled the panel with instructions before a word had been typed. The
 * labels are gone — they explained the router, which is not the reader's problem — and the questions
 * are now single lines a glance can take in.
 */
function EmptyState({ onPick }: { onPick: (question: string) => void }) {
  return (
    <div className="flex h-full flex-col justify-center px-1 pb-6">
      <div className="flex items-center gap-2.5">
        <Logo size={20} />
        <p className="text-sm font-medium">Ask about this batch</p>
      </div>

      <p className="mt-2.5 text-[13px] leading-relaxed" style={{ color: 'var(--ink-muted)' }}>
        Every answer is built from rows that were actually retrieved. If nothing matches, it says so
        rather than guessing.
      </p>

      <div className="mt-5 space-y-1.5">
        {SUGGESTIONS.map((suggestion) => (
          <button
            key={suggestion}
            type="button"
            onClick={() => onPick(suggestion)}
            className="flex w-full items-center gap-2 rounded-lg px-2.5 py-2 text-left text-[13px] transition-colors"
            style={{ background: 'var(--surface)', color: 'var(--ink-muted)' }}
          >
            <span className="shrink-0" style={{ color: 'var(--accent)' }}>
              →
            </span>
            <span className="min-w-0 truncate">{suggestion}</span>
          </button>
        ))}
      </div>
    </div>
  )
}
