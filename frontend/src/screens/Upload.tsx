import { motion } from 'framer-motion'
import { useState } from 'react'
import { api, loadSampleFiles } from '../api/client'
import { StepProgress, type StepState } from '../components/StepProgress'
import { ErrorState } from '../components/States'
import { pageTransition } from '../lib/motion'
import { prefetchNarrative } from '../lib/narrative'

type ZoneKey = 'orders' | 'settlement' | 'bank'

const EXPECTED: Record<ZoneKey, { title: string; columns: string[] }> = {
  orders: {
    title: 'Orders',
    columns: ['order_id', 'order_ts', 'amount', 'payment_id', 'method', 'payment_status', 'dispute_status', 'dispute_opened_at'],
  },
  settlement: {
    title: 'Razorpay settlement',
    columns: ['utr', 'settled_on', 'entity_type', 'entity_id', 'order_id', 'method', 'gross_amount', 'fee', 'gst', 'net_amount'],
  },
  bank: {
    title: 'Bank statement',
    columns: ['entry_date', 'description', 'utr', 'credit_amount'],
  },
}

type Parsed = { file: File; rows: number; missing: string[] }

async function inspect(file: File, expected: string[]): Promise<Parsed> {
  const text = await file.text()
  const lines = text.split(/\r?\n/).filter((line) => line.trim().length > 0)
  const header = (lines[0] ?? '').split(',').map((column) => column.trim())
  return { file, rows: Math.max(0, lines.length - 1), missing: expected.filter((column) => !header.includes(column)) }
}

export function Upload({ onReconciled }: { onReconciled: (batchId: string) => void }) {
  const [files, setFiles] = useState<Partial<Record<ZoneKey, Parsed>>>({})
  const [steps, setSteps] = useState<{ label: string; state: StepState }[] | null>(null)
  const [error, setError] = useState<unknown>(null)

  const ready = (['orders', 'settlement', 'bank'] as ZoneKey[]).every(
    (key) => files[key] && files[key]!.missing.length === 0,
  )

  async function accept(key: ZoneKey, file: File) {
    setError(null)
    setFiles((current) => ({ ...current, [key]: undefined }))
    const parsed = await inspect(file, EXPECTED[key].columns)
    setFiles((current) => ({ ...current, [key]: parsed }))
  }

  async function useSample() {
    setError(null)
    try {
      const sample = await loadSampleFiles()
      await Promise.all([accept('orders', sample.orders), accept('settlement', sample.settlement), accept('bank', sample.bank)])
    } catch (sampleError) {
      setError(sampleError)
    }
  }

  async function reconcile() {
    if (!ready) return
    setError(null)
    setSteps([
      { label: 'Ingest', state: 'running' },
      { label: 'Match', state: 'pending' },
      { label: 'Rules', state: 'pending' },
      { label: 'Exceptions', state: 'pending' },
    ])
    try {
      const ingested = await api.ingestCsv({
        orders: files.orders!.file,
        settlement: files.settlement!.file,
        bank: files.bank!.file,
      })
      setSteps([
        { label: 'Ingest', state: 'done' },
        { label: 'Match', state: 'running' },
        { label: 'Rules', state: 'pending' },
        { label: 'Exceptions', state: 'pending' },
      ])
      // One call runs matching, the rules engine and exception detection in that order.
      await api.reconcile(ingested.batchId)
      setSteps([
        { label: 'Ingest', state: 'done' },
        { label: 'Match', state: 'done' },
        { label: 'Rules', state: 'done' },
        { label: 'Exceptions', state: 'done' },
      ])
      // Started here, not on the Waterfall, so opening that screen never spends a model call.
      // Deliberately not awaited: the narration takes about ten seconds and the numbers do not.
      prefetchNarrative(ingested.batchId)
      onReconciled(ingested.batchId)
    } catch (reconcileError) {
      setError(reconcileError)
      setSteps(null)
    }
  }

  return (
    <motion.div {...pageTransition} className="mx-auto max-w-5xl p-10">
      <header className="mb-8">
        <h1 className="text-xl font-semibold">Reconcile a batch</h1>
        <p className="mt-1 text-sm" style={{ color: 'var(--ink-muted)' }}>
          Three files: what you sold, what Razorpay settled, and what your bank received.{' '}
          <button type="button" onClick={useSample} className="underline" style={{ color: 'var(--accent)' }}>
            Load sample data
          </button>
        </p>
      </header>

      <div className="grid grid-cols-3 gap-4">
        {(Object.keys(EXPECTED) as ZoneKey[]).map((key) => (
          <DropZone key={key} zone={key} parsed={files[key]} onFile={(file) => void accept(key, file)} />
        ))}
      </div>

      {error != null && (
        <div className="mt-6">
          <ErrorState error={error} />
        </div>
      )}

      {steps && (
        <div className="card mt-6 px-5 py-4">
          <StepProgress steps={steps} />
        </div>
      )}

      <button
        type="button"
        disabled={!ready || steps !== null}
        onClick={() => void reconcile()}
        className="mt-6 rounded-lg px-4 py-2 text-sm font-medium disabled:cursor-not-allowed"
        style={{
          background: ready && !steps ? 'var(--accent)' : 'var(--line)',
          color: ready && !steps ? '#fff' : 'var(--ink-faint)',
        }}
      >
        Reconcile
      </button>
    </motion.div>
  )
}

function DropZone({ zone, parsed, onFile }: { zone: ZoneKey; parsed?: Parsed; onFile: (file: File) => void }) {
  const [hovering, setHovering] = useState(false)
  const expected = EXPECTED[zone]
  const failed = parsed && parsed.missing.length > 0
  const passed = parsed && parsed.missing.length === 0

  const borderColor = failed ? 'var(--lost)' : passed ? 'var(--received)' : hovering ? 'var(--accent)' : 'var(--line)'

  return (
    <label
      onDragOver={(event) => {
        event.preventDefault()
        setHovering(true)
      }}
      onDragLeave={() => setHovering(false)}
      onDrop={(event) => {
        event.preventDefault()
        setHovering(false)
        const file = event.dataTransfer.files?.[0]
        if (file) onFile(file)
      }}
      className="flex min-h-[190px] cursor-pointer flex-col rounded-xl border-2 border-dashed p-4 transition-colors"
      style={{ borderColor, background: 'var(--surface-raised)' }}
    >
      <input
        type="file"
        accept=".csv,text/csv"
        className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0]
          if (file) onFile(file)
        }}
      />
      <p className="text-sm font-medium">{expected.title}</p>

      {!parsed && (
        <p className="ref mt-2 leading-relaxed" style={{ color: 'var(--ink-faint)' }}>
          {expected.columns.join(', ')}
        </p>
      )}

      {parsed && (
        <div className="mt-2">
          <p className="ref" style={{ color: 'var(--ink-muted)' }}>
            {parsed.file.name}
          </p>
          <p className="mt-1 text-xs" style={{ color: 'var(--ink-muted)' }}>
            {parsed.rows} rows
          </p>
          {passed && (
            <p className="mt-2 text-xs" style={{ color: 'var(--received)' }}>
              ✓ all expected columns present
            </p>
          )}
          {failed && (
            <p className="mt-2 text-xs" style={{ color: 'var(--lost)' }}>
              missing: {parsed.missing.join(', ')}
            </p>
          )}
        </div>
      )}

      <span className="mt-auto pt-3 text-xs" style={{ color: 'var(--ink-faint)' }}>
        drop a CSV, or click to choose
      </span>
    </label>
  )
}
