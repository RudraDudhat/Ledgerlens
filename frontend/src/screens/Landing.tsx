import { motion } from 'framer-motion'
import { Logo } from '../components/Logo'
import { rupees } from '../lib/format'
import { usePrefersReducedMotion } from '../lib/motion'

/**
 * Everything on this page is a real number from the committed 300-order batch, produced by the test
 * suite rather than chosen for effect — including the parts that are unflattering. A reconciliation
 * tool that overstated itself on its own front page would be arguing against its own product.
 *
 * <p>The four semantic colours are deliberately not used for decoration here. They mean received,
 * held, lost and fees everywhere else in the app, and spending them on feature cards would cost
 * their meaning before the user ever reaches a table.
 */

const WATERFALL: { label: string; amount: number; tone: string }[] = [
  { label: 'Gross sales', amount: 1554691.47, tone: 'var(--accent)' },
  { label: 'Failed payments', amount: -86606.98, tone: 'var(--lost)' },
  { label: 'Razorpay fees', amount: -13967.3, tone: 'var(--fees)' },
  { label: 'GST on fees', amount: -2514.12, tone: 'var(--fees)' },
  { label: 'Held for disputes', amount: -48069.24, tone: 'var(--held)' },
  { label: 'Refunds', amount: -76027.33, tone: 'var(--lost)' },
  { label: 'Not credited by bank', amount: -108557.71, tone: 'var(--held)' },
  { label: 'Unmatched bank credits', amount: 226173.9, tone: 'var(--received)' },
  { label: 'Bank amount differences', amount: 33, tone: 'var(--fees)' },
]

/** The three files, in the order a merchant would think of them: sold, paid out, arrived. */
const INPUTS = [
  { file: 'orders.csv', from: 'your store', carries: 'what you sold' },
  { file: 'razorpay_settlement.csv', from: 'Razorpay', carries: 'what it says it paid out' },
  { file: 'bank_statement.csv', from: 'your bank', carries: 'what actually arrived' },
]

/** What the three files turn into. The numbers match the steps in the sidebar once you are inside. */
const OUTPUTS = [
  {
    step: '2',
    title: 'Reconciliation',
    body: 'Every order matched to a payout and a bank credit — or flagged, with the reason it could not be.',
  },
  {
    step: '3',
    title: 'Waterfall',
    body: 'Each rupee between sales and bank, named and counted exactly once.',
  },
  {
    step: '4',
    title: 'Forecast',
    body: 'What is still due to land, and which day it should arrive.',
  },
  {
    step: '/',
    title: 'Ask',
    body: 'Plain questions answered from the rows themselves, citing the ones it used.',
  },
]

const CAPABILITIES = [
  {
    title: 'Match what can be matched',
    body: 'Exact order id and amount first, then the bank UTR, then amount within a date window. Every pairing is one to one, so a settlement credited twice leaves the second row unclaimed instead of being absorbed.',
    proof: '92.00% of 300 orders',
  },
  {
    title: 'Explain the rest',
    body: 'Each unmatched record gets a status, a reason in plain English, and a confidence that reflects how it was reached — certain when read from a column, lower when inferred from something being absent.',
    proof: '50 of 50 injected anomalies found',
  },
  {
    title: 'Forecast what is still coming',
    body: 'Payments too late in the window to have settled, and money held behind a dispute, placed on the day each is expected. A dispute with no known release date is left off rather than promised.',
    proof: 'release dates matched exactly',
  },
  {
    title: 'Answer questions from rows',
    body: 'Order ids, dates and UTRs in your question are looked up directly and the answer cites the rows it used. If retrieval finds nothing, it says so instead of inventing a number.',
    proof: 'every answer is checkable',
  },
]

export function Landing({ onStart }: { onStart: () => void }) {
  const reduced = usePrefersReducedMotion()
  const widest = Math.max(...WATERFALL.map((step) => Math.abs(step.amount)))

  /** The hero is on screen at load, so it animates on mount rather than on scroll. */
  const rise = (delay: number) =>
    reduced
      ? {}
      : {
          initial: { opacity: 0, y: 8 },
          animate: { opacity: 1, y: 0 },
          transition: { duration: 0.35, delay, ease: 'easeOut' as const },
        }

  /**
   * Everything below the fold animates when it is reached, not at load. Animating a section the
   * reader cannot see spends the motion on nobody and leaves them scrolling into a static page.
   */
  const reveal = (delay = 0) =>
    reduced
      ? {}
      : {
          initial: { opacity: 0, y: 12 },
          whileInView: { opacity: 1, y: 0 },
          viewport: { once: true, amount: 0.2 },
          transition: { duration: 0.4, delay, ease: 'easeOut' as const },
        }

  /**
   * A row that staggers its own children.
   *
   * <p>The observer goes on the row, never on the cards. The page has a 1280px floor, so in a
   * narrower window the last column of a four-column grid sits off the right edge — and a card
   * watching for its own visibility there is never seen, so it never animates and stays at zero
   * opacity for good. A full-width row always intersects; its children follow it in.
   */
  const stagger = (gap = 0.08) =>
    reduced
      ? {}
      : {
          initial: 'hidden' as const,
          whileInView: 'shown' as const,
          viewport: { once: true, amount: 0.2 },
          variants: { shown: { transition: { staggerChildren: gap } } },
        }

  const item = reduced
    ? {}
    : {
        variants: {
          hidden: { opacity: 0, y: 12 },
          shown: { opacity: 1, y: 0, transition: { duration: 0.4, ease: 'easeOut' as const } },
        },
      }

  return (
    <div className="h-screen min-w-[1280px] overflow-y-auto">
      <div className="mx-auto max-w-6xl px-10 py-20">
        <motion.header {...rise(0)}>
          <div className="flex items-center gap-3">
            <Logo size={40} title="ledgerlens" />
            <p className="ref text-xs uppercase tracking-[0.2em]" style={{ color: 'var(--ink-faint)' }}>
              ledgerlens
            </p>
          </div>
          <h1 className="mt-5 max-w-3xl text-5xl font-semibold leading-[1.1]">
            Every rupee between what you sold and what hit your bank.
          </h1>
          <p className="mt-5 max-w-2xl text-base leading-relaxed" style={{ color: 'var(--ink-muted)' }}>
            Give it your order export, your Razorpay settlement report and your bank statement. It
            reconciles the three, accounts for the difference to the rupee, and tells you which
            records it could not explain — and how sure it is about each one.
          </p>

          <div className="mt-8 flex items-center gap-4">
            <button
              type="button"
              onClick={onStart}
              className="rounded-lg px-5 py-2.5 text-sm font-medium"
              style={{ background: 'var(--accent)', color: '#fff', boxShadow: 'var(--shadow)' }}
            >
              Reconcile a batch
            </button>
            <span className="text-xs" style={{ color: 'var(--ink-faint)' }}>
              A 300-order sample batch is built in — no data of your own needed.
            </span>
          </div>
        </motion.header>

        {/*
          Three files in, one pass, four ways to read the result. Placed before any of the detail
          below, because someone who does not yet know what the tool consumes cannot make sense of a
          waterfall of its output.
        */}
        <section className="mt-20">
          <motion.div {...reveal()}>
            <h2 className="text-lg font-semibold">How it works</h2>
            <p className="mt-1 text-sm" style={{ color: 'var(--ink-muted)' }}>
              Three files go in. Everything else is read back out of what they agree and disagree on.
            </p>
          </motion.div>

          <motion.div {...stagger()} className="mt-8 grid grid-cols-3 gap-4">
            {INPUTS.map((input) => (
              <motion.div key={input.file} {...item} className="card p-5">
                <p className="ref text-xs" style={{ color: 'var(--accent)' }}>
                  {input.file}
                </p>
                <p className="mt-2 text-sm">{input.carries}</p>
                <p className="mt-1 text-xs" style={{ color: 'var(--ink-faint)' }}>
                  from {input.from}
                </p>
              </motion.div>
            ))}
          </motion.div>

          <Flow reduced={reduced} />

          <motion.div {...reveal()} className="card p-6" style={{ borderColor: 'var(--accent)' }}>
            <div className="flex items-baseline justify-between">
              <p className="text-sm font-semibold">One reconciliation pass</p>
              <span className="ref text-xs" style={{ color: 'var(--ink-faint)' }}>
                rules first, model only where rules ran out
              </span>
            </div>
            <div className="mt-4 grid grid-cols-3 gap-4">
              {[
                { n: '1', text: 'Match every order to a payout and a bank credit, one to one.' },
                { n: '2', text: 'Explain each record that would not match, and say how sure it is.' },
                { n: '3', text: 'Account for the whole gap, to the rupee, with nothing absorbed.' },
              ].map((phase) => (
                <div key={phase.n} className="flex gap-2.5">
                  <span
                    className="ref flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[10px]"
                    style={{ background: 'var(--accent-soft)', color: 'var(--accent)' }}
                  >
                    {phase.n}
                  </span>
                  <p className="text-sm leading-relaxed" style={{ color: 'var(--ink-muted)' }}>
                    {phase.text}
                  </p>
                </div>
              ))}
            </div>
          </motion.div>

          <Flow reduced={reduced} />

          <motion.div {...stagger()} className="grid grid-cols-4 gap-4">
            {OUTPUTS.map((output) => (
              <motion.div key={output.title} {...item} className="card p-5">
                <span
                  className="ref inline-flex h-5 w-5 items-center justify-center rounded text-[10px]"
                  style={{ background: 'var(--line)', color: 'var(--ink-muted)' }}
                >
                  {output.step}
                </span>
                <p className="mt-2.5 text-sm font-medium">{output.title}</p>
                <p className="mt-1.5 text-xs leading-relaxed" style={{ color: 'var(--ink-muted)' }}>
                  {output.body}
                </p>
              </motion.div>
            ))}
          </motion.div>
        </section>

        <motion.section {...reveal()} className="card mt-20 p-8">
          <div className="flex items-baseline justify-between">
            <div>
              <h2 className="text-lg font-semibold">The gap, accounted for</h2>
              <p className="mt-1 text-sm" style={{ color: 'var(--ink-muted)' }}>
                Every bar is summed from stored rows. Nothing is estimated, and nothing is rounded
                for display.
              </p>
            </div>
            <span className="ref text-xs" style={{ color: 'var(--ink-faint)' }}>
              sample batch · seed 42
            </span>
          </div>

          {/* One observer on the list; every bar grows off the same trigger, in order. */}
          <motion.ul {...stagger(0.05)} className="mt-8 space-y-2.5">
            {WATERFALL.map((step) => (
              <motion.li
                key={step.label}
                {...(reduced
                  ? {}
                  : { variants: { hidden: { opacity: 0 }, shown: { opacity: 1 } } })}
                className="grid items-center gap-4"
                style={{ gridTemplateColumns: '220px 1fr 160px' }}
              >
                <span className="truncate text-sm" style={{ color: 'var(--ink-muted)' }}>
                  {step.label}
                </span>
                <span className="h-1.5 rounded-full" style={{ background: 'var(--line)' }}>
                  <motion.span
                    className="block h-full rounded-full"
                    style={{
                      background: step.tone,
                      width: reduced ? `${(Math.abs(step.amount) / widest) * 100}%` : undefined,
                    }}
                    {...(reduced
                      ? {}
                      : {
                          variants: {
                            hidden: { width: 0 },
                            shown: {
                              width: `${(Math.abs(step.amount) / widest) * 100}%`,
                              transition: { duration: 0.5, ease: 'easeOut' as const },
                            },
                          },
                        })}
                  />
                </span>
                <span className="amount text-sm" style={{ color: step.tone }}>
                  {step.amount < 0 ? '−' : '+'}
                  {rupees(Math.abs(step.amount)).slice(1)}
                </span>
              </motion.li>
            ))}
          </motion.ul>

          <div
            className="mt-8 flex items-baseline justify-between border-t pt-5"
            style={{ borderColor: 'var(--line)' }}
          >
            <span className="text-sm font-medium">Reached the bank</span>
            <span className="flex items-baseline gap-3">
              <span className="text-xs" style={{ color: 'var(--received)' }}>
                reconciles to the rupee
              </span>
              <span className="amount text-2xl font-medium">{rupees(1445155.69)}</span>
            </span>
          </div>
        </motion.section>

        <motion.section {...reveal()} className="mt-20">
          <h2 className="text-lg font-semibold">What it does</h2>
          <div className="mt-6 grid grid-cols-2 gap-4">
            {CAPABILITIES.map((capability) => (
              <div key={capability.title} className="card flex flex-col p-6">
                <h3 className="text-sm font-semibold">{capability.title}</h3>
                <p className="mt-2 flex-1 text-sm leading-relaxed" style={{ color: 'var(--ink-muted)' }}>
                  {capability.body}
                </p>
                <p className="ref mt-4 text-xs" style={{ color: 'var(--accent)' }}>
                  {capability.proof}
                </p>
              </div>
            ))}
          </div>
        </motion.section>

        <motion.section {...reveal()} className="card mt-20 p-8">
          <h2 className="text-lg font-semibold">Measured, not claimed</h2>
          <p className="mt-1 max-w-3xl text-sm" style={{ color: 'var(--ink-muted)' }}>
            These come from the test suite on the committed batch, where every injected anomaly is
            known in advance.
          </p>

          <div className="mt-7 grid grid-cols-4 gap-6">
            {[
              { value: '92.00%', label: 'orders matched', note: 'against an 88% clean-record floor' },
              { value: '50 / 50', label: 'anomalies found', note: 'no false positives, none missed' },
              { value: '₹0.00', label: 'left unexplained', note: 'the waterfall closes exactly' },
              { value: '0', label: 'unknown records', note: 'nothing quietly absorbed' },
            ].map((metric) => (
              <div key={metric.label}>
                <p className="amount text-3xl font-medium" style={{ textAlign: 'left' }}>
                  {metric.value}
                </p>
                <p className="mt-1 text-sm">{metric.label}</p>
                <p className="mt-1 text-xs leading-snug" style={{ color: 'var(--ink-faint)' }}>
                  {metric.note}
                </p>
              </div>
            ))}
          </div>

          <p
            className="mt-7 border-t pt-5 text-xs leading-relaxed"
            style={{ borderColor: 'var(--line)', color: 'var(--ink-muted)' }}
          >
            <span style={{ color: 'var(--held)' }}>Read those honestly:</span> the sample batch is
            synthetic, and every anomaly in it is directly observable in the three files, so a correct
            rule finds all of them. Real data contains ambiguity this batch does not, and the scores
            would drop. The figure worth trusting is the last one — nothing was left unexplained or
            silently absorbed.
          </p>
        </motion.section>

        <motion.section {...reveal()} className="mt-20">
          <h2 className="text-lg font-semibold">Where the model is, and is not</h2>
          <div className="mt-6 grid grid-cols-3 gap-4">
            <div className="card p-6">
              <p className="ref text-xs" style={{ color: 'var(--ink-faint)' }}>
                first
              </p>
              <p className="mt-2 text-sm font-medium">Deterministic rules</p>
              <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--ink-muted)' }}>
                Fees, GST, settlement cycles, refund timing and dispute holds are arithmetic with a
                right answer. They settle everything they can, on their own.
              </p>
            </div>
            <div className="card p-6">
              <p className="ref text-xs" style={{ color: 'var(--ink-faint)' }}>
                then
              </p>
              <p className="mt-2 text-sm font-medium">The model, narrowly</p>
              <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--ink-muted)' }}>
                It classifies only what the rules could not, narrates a waterfall that was already
                computed, and answers from rows already retrieved. It never produces a number.
              </p>
            </div>
            <div className="card p-6">
              <p className="ref text-xs" style={{ color: 'var(--ink-faint)' }}>
                always
              </p>
              <p className="mt-2 text-sm font-medium">On the record</p>
              <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--ink-muted)' }}>
                Every model call is written to an append-only audit log with the prompt hash, model,
                latency and output, so any answer traces back to what produced it.
              </p>
            </div>
          </div>
        </motion.section>

        <motion.footer
          {...reveal()}
          className="mt-20 flex items-center justify-between border-t pt-8"
          style={{ borderColor: 'var(--line)' }}
        >
          <p className="text-sm" style={{ color: 'var(--ink-muted)' }}>
            Start with the built-in sample, or drop in your own three files.
          </p>
          <button
            type="button"
            onClick={onStart}
            className="rounded-lg px-5 py-2.5 text-sm font-medium"
            style={{ background: 'var(--accent)', color: '#fff' }}
          >
            Reconcile a batch
          </button>
        </motion.footer>
      </div>
    </div>
  )
}

/**
 * The line between one stage of the flow and the next, drawn downward as it is scrolled to.
 *
 * <p>Decoration, and marked as such: it carries no information the cards either side do not already
 * state, so a screen reader is better off skipping it entirely.
 */
function Flow({ reduced }: { reduced: boolean }) {
  return (
    <div className="flex flex-col items-center py-5" aria-hidden="true">
      <motion.span
        className="block w-px"
        style={{ height: 26, background: 'var(--line)', transformOrigin: 'top' }}
        {...(reduced
          ? {}
          : {
              initial: { scaleY: 0 },
              whileInView: { scaleY: 1 },
              viewport: { once: true },
              transition: { duration: 0.35, ease: 'easeOut' as const },
            })}
      />
      <motion.span
        className="mt-1 block h-1.5 w-1.5 rounded-full"
        style={{ background: 'var(--accent)' }}
        {...(reduced
          ? {}
          : {
              initial: { opacity: 0, scale: 0 },
              whileInView: { opacity: 1, scale: 1 },
              viewport: { once: true },
              transition: { duration: 0.25, delay: 0.3, ease: 'easeOut' as const },
            })}
      />
    </div>
  )
}
