import { AnimatePresence } from 'framer-motion'
import { useCallback, useState } from 'react'
import { AskPanel } from './components/AskPanel'
import type { AlertView } from './api/client'
import { Drawer, type DrawerSubject } from './components/Drawer'
import { Forecast } from './screens/Forecast'
import { Landing } from './screens/Landing'
import { Reconciliation, type Row } from './screens/Reconciliation'
import { Upload } from './screens/Upload'
import { Waterfall } from './screens/Waterfall'

type Screen = 'landing' | 'upload' | 'reconciliation' | 'waterfall' | 'forecast'

const SCREENS: { key: Screen; label: string; step: number }[] = [
  { key: 'upload', label: 'Upload', step: 1 },
  { key: 'reconciliation', label: 'Reconciliation', step: 2 },
  { key: 'waterfall', label: 'Waterfall', step: 3 },
  { key: 'forecast', label: 'Forecast', step: 4 },
]

export default function App() {
  const [screen, setScreen] = useState<Screen>('landing')
  const [batchId, setBatchId] = useState<string | null>(null)
  const [drawer, setDrawer] = useState<DrawerSubject | null>(null)
  const [askOpen, setAskOpen] = useState(false)
  const [pendingQuestion, setPendingQuestion] = useState<string | null>(null)

  const openRow = useCallback((row: Row) => {
    setDrawer({ kind: 'row', orderId: row.orderId, match: row.match, exception: row.exception })
  }, [])

  const openAlert = useCallback((alert: AlertView, failureRateByHour: Record<string, number>) => {
    setDrawer({ kind: 'alert', alert, failureRateByHour })
  }, [])

  const openRows = useCallback((title: string, rowIds: number[]) => {
    setDrawer({ kind: 'rows', title, rowIds })
  }, [])

  const askAbout = useCallback((question: string) => {
    setPendingQuestion(question)
    setDrawer(null)
  }, [])

  if (screen === 'landing') {
    return <Landing onStart={() => setScreen('upload')} />
  }

  return (
    <div className="flex h-screen min-w-[1280px]">
      <nav className="flex w-56 shrink-0 flex-col border-r px-3 py-6" style={{ borderColor: 'var(--line)' }}>
        <button type="button" onClick={() => setScreen('landing')} className="px-3 text-left">
          <p className="text-sm font-semibold">ledgerlens</p>
          <p className="mt-1 text-xs leading-snug" style={{ color: 'var(--ink-faint)' }}>
            Every rupee between what you sold and what hit your bank.
          </p>
        </button>

        <ul className="mt-8 space-y-1">
          {SCREENS.map((item) => {
            const locked = item.key !== 'upload' && !batchId
            const active = screen === item.key
            return (
              <li key={item.key}>
                <button
                  type="button"
                  disabled={locked}
                  onClick={() => setScreen(item.key)}
                  className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm disabled:cursor-not-allowed"
                  style={{
                    background: active ? 'var(--accent-soft)' : 'transparent',
                    color: locked ? 'var(--ink-faint)' : active ? 'var(--accent)' : 'var(--ink-muted)',
                  }}
                  title={locked ? 'Reconcile a batch first' : undefined}
                >
                  <span className="ref">{item.step}</span>
                  {item.label}
                </button>
              </li>
            )
          })}
        </ul>

        {batchId && (
          <p className="ref mt-auto px-3 pt-6" style={{ color: 'var(--ink-faint)' }}>
            Reference {batchId.slice(0, 8)}
          </p>
        )}
      </nav>

      {/* The collapsed Ask tab is fixed to the right edge, so reserve a gutter it can sit in
          rather than letting it cover the table and its scrollbar. */}
      <main
        className="min-w-0 flex-1 overflow-hidden"
        style={{ paddingRight: batchId && screen !== 'upload' && !askOpen ? 44 : 0 }}
      >
        <AnimatePresence mode="wait">
          {screen === 'upload' && (
            <Upload
              key="upload"
              onReconciled={(id) => {
                setBatchId(id)
                setScreen('reconciliation')
              }}
            />
          )}
          {screen === 'reconciliation' && batchId && (
            <Reconciliation key="reconciliation" batchId={batchId} onOpenRow={openRow} onOpenAlert={openAlert} />
          )}
          {screen === 'waterfall' && batchId && (
            <Waterfall key="waterfall" batchId={batchId} onOpenRows={openRows} onAskAbout={askAbout} />
          )}
          {screen === 'forecast' && batchId && <Forecast key="forecast" batchId={batchId} />}
        </AnimatePresence>
      </main>

      {batchId && screen !== 'upload' && (
        <AskPanel
          batchId={batchId}
          open={askOpen}
          onToggle={() => setAskOpen((open) => !open)}
          pendingQuestion={pendingQuestion}
          onPendingConsumed={() => setPendingQuestion(null)}
        />
      )}

      <Drawer subject={drawer} onClose={() => setDrawer(null)} onAskAbout={askAbout} />
    </div>
  )
}
