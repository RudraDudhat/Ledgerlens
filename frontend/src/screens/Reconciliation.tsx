import { flexRender, getCoreRowModel, useReactTable, type ColumnDef } from '@tanstack/react-table'
import { motion } from 'framer-motion'
import { useEffect, useMemo, useRef, useState } from 'react'
import { api, type ExceptionView, type MatchView, type ReconcileSummary } from '../api/client'
import { StatCard } from '../components/StatCard'
import { StatusBadge, semanticColor } from '../components/StatusBadge'
import { StatusStrip } from '../components/StatusStrip'
import { SkeletonCards, SkeletonRows } from '../components/Skeleton'
import { ErrorState } from '../components/States'
import { percent, rupees, shortDate } from '../lib/format'
import { pageTransition } from '../lib/motion'

export type Row = {
  orderId: string
  amount: number | null
  method: string | null
  status: string
  settledOn: string | null
  utr: string | null
  confidence: number | null
  match?: MatchView
  exception?: ExceptionView
}

const ROW_HEIGHT = 40
const OVERSCAN = 8

export function Reconciliation({
  batchId,
  onOpenRow,
}: {
  batchId: string
  onOpenRow: (row: Row) => void
}) {
  const [summary, setSummary] = useState<ReconcileSummary | null>(null)
  const [rows, setRows] = useState<Row[] | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [statusFilter, setStatusFilter] = useState<string | null>(null)
  const [search, setSearch] = useState('')
  const [activeIndex, setActiveIndex] = useState(0)
  const [scrollTop, setScrollTop] = useState(0)
  const [viewportHeight, setViewportHeight] = useState(600)
  const bodyRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let cancelled = false
    setSummary(null)
    setRows(null)
    setError(null)
    Promise.all([api.summary(batchId), api.matches(batchId), api.exceptions(batchId)])
      .then(([loadedSummary, matches, exceptions]) => {
        if (cancelled) return
        setSummary(loadedSummary)
        setRows(buildRows(matches.content, exceptions))
      })
      .catch((loadError) => !cancelled && setError(loadError))
    return () => {
      cancelled = true
    }
  }, [batchId])

  const filtered = useMemo(() => {
    if (!rows) return []
    const needle = search.trim().toLowerCase()
    return rows.filter((row) => {
      if (statusFilter && row.status !== statusFilter) return false
      if (!needle) return true
      return (
        row.orderId.toLowerCase().includes(needle) ||
        (row.utr ?? '').toLowerCase().includes(needle) ||
        (row.amount ?? '').toString().includes(needle)
      )
    })
  }, [rows, statusFilter, search])

  const columns = useMemo<ColumnDef<Row>[]>(
    () => [
      { header: 'Order', accessorKey: 'orderId', cell: (cell) => <span className="ref">{cell.getValue<string>()}</span> },
      {
        header: 'Amount',
        accessorKey: 'amount',
        cell: (cell) => <span className="amount block">{rupees(cell.getValue<number | null>())}</span>,
      },
      { header: 'Method', accessorKey: 'method', cell: (cell) => cell.getValue<string | null>() ?? '—' },
      { header: 'Status', accessorKey: 'status', cell: (cell) => <StatusBadge status={cell.getValue<string>()} /> },
      { header: 'Settled on', accessorKey: 'settledOn', cell: (cell) => shortDate(cell.getValue<string | null>()) },
      {
        header: 'Bank UTR',
        accessorKey: 'utr',
        cell: (cell) => <span className="ref">{cell.getValue<string | null>() ?? '—'}</span>,
      },
      {
        header: 'Confidence',
        accessorKey: 'confidence',
        cell: (cell) => {
          const value = cell.getValue<number | null>()
          return <span className="amount block">{value === null ? '—' : value.toFixed(3)}</span>
        },
      },
    ],
    [],
  )

  const table = useReactTable({ data: filtered, columns, getCoreRowModel: getCoreRowModel() })

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLInputElement) return
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp' && event.key !== 'Enter') return
      event.preventDefault()
      if (event.key === 'Enter') {
        const row = filtered[activeIndex]
        if (row) onOpenRow(row)
        return
      }
      setActiveIndex((current) => {
        const next = Math.max(0, Math.min(filtered.length - 1, current + (event.key === 'ArrowDown' ? 1 : -1)))
        bodyRef.current?.scrollTo({ top: Math.max(0, next * ROW_HEIGHT - viewportHeight / 2) })
        return next
      })
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [filtered, activeIndex, viewportHeight, onOpenRow])

  useEffect(() => {
    const element = bodyRef.current
    if (!element) return
    setViewportHeight(element.clientHeight)
  }, [rows])

  if (error != null) {
    return (
      <div className="p-10">
        <ErrorState error={error} onRetry={() => setSearch((value) => value)} />
      </div>
    )
  }

  if (!summary || !rows) {
    return (
      <div className="space-y-6 p-10">
        <SkeletonCards />
        <SkeletonRows rows={10} />
      </div>
    )
  }

  // Only the rows in view are mounted; three hundred is fine but a real month is not.
  const first = Math.max(0, Math.floor(scrollTop / ROW_HEIGHT) - OVERSCAN)
  const last = Math.min(filtered.length, Math.ceil((scrollTop + viewportHeight) / ROW_HEIGHT) + OVERSCAN)
  const visible = table.getRowModel().rows.slice(first, last)

  return (
    <motion.div {...pageTransition} className="flex h-full flex-col p-8">
      <div className="grid grid-cols-3 gap-4">
        <StatCard label="Match rate" countUpTo={summary.orderMatchRate} format={percent} emphasis />
        <StatCard label="Sales total" value={rupees(summary.grossSales)} />
        <StatCard label="Bank received" value={rupees(summary.totalBankCredits)} />
      </div>

      <div className="mt-5">
        <StatusStrip counts={summary.countsByStatus} />
      </div>

      <div className="mt-6 flex items-center gap-2">
        <div className="flex flex-wrap gap-1.5">
          <FilterChip label="all" count={rows.length} active={statusFilter === null} onClick={() => setStatusFilter(null)} />
          {Object.entries(summary.countsByStatus)
            .filter(([, count]) => count > 0)
            .map(([status, count]) => (
              <FilterChip
                key={status}
                label={status.replace(/_/g, ' ').toLowerCase()}
                count={count}
                color={semanticColor(status)}
                active={statusFilter === status}
                onClick={() => setStatusFilter(statusFilter === status ? null : status)}
              />
            ))}
        </div>
        <input
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder="order id, UTR or amount"
          className="ml-auto w-64 rounded-lg border px-3 py-1.5 text-sm outline-none"
          style={{ background: 'var(--surface-raised)', borderColor: 'var(--line)', color: 'var(--ink)' }}
        />
      </div>

      <div className="card mt-4 flex min-h-0 flex-1 flex-col overflow-hidden">
        <div className="sticky top-0 z-10 grid border-b px-4" style={{ background: 'var(--surface-raised)', borderColor: 'var(--line)', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))' }}>
          {table.getHeaderGroups()[0]?.headers.map((header, index) => (
            <div
              key={header.id}
              className="py-2 text-xs font-medium uppercase tracking-wider"
              style={{ color: 'var(--ink-faint)', textAlign: index === 1 || index === 6 ? 'right' : 'left' }}
            >
              {flexRender(header.column.columnDef.header, header.getContext())}
            </div>
          ))}
        </div>

        <div ref={bodyRef} className="min-h-0 flex-1 overflow-y-auto" onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}>
          {filtered.length === 0 ? (
            <p className="p-10 text-center text-sm" style={{ color: 'var(--ink-muted)' }}>
              No rows match that filter.
            </p>
          ) : (
            <div style={{ height: filtered.length * ROW_HEIGHT, position: 'relative' }}>
              {visible.map((row) => {
                const index = row.index
                return (
                  <div
                    key={row.id}
                    onClick={() => {
                      setActiveIndex(index)
                      onOpenRow(row.original)
                    }}
                    className="absolute grid w-full cursor-pointer items-center px-4 text-sm"
                    style={{
                      top: index * ROW_HEIGHT,
                      height: ROW_HEIGHT,
                      gridTemplateColumns: 'repeat(7, minmax(0, 1fr))',
                      background: index === activeIndex ? 'var(--accent-soft)' : 'transparent',
                      borderBottom: '1px solid var(--line)',
                    }}
                  >
                    {row.getVisibleCells().map((cell) => (
                      <div key={cell.id} className="truncate pr-2">
                        {flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </div>
                    ))}
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>
    </motion.div>
  )
}

function FilterChip({
  label,
  count,
  active,
  color,
  onClick,
}: {
  label: string
  count: number
  active: boolean
  color?: string
  onClick: () => void
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs"
      style={{
        borderColor: active ? (color ?? 'var(--accent)') : 'var(--line)',
        color: active ? (color ?? 'var(--accent)') : 'var(--ink-muted)',
        background: active ? 'var(--surface-raised)' : 'transparent',
      }}
    >
      {color && <span className="h-1.5 w-1.5 rounded-full" style={{ background: color }} />}
      {label} <span className="ref">{count}</span>
    </button>
  )
}

/**
 * One row per order. Matched orders come from the matches endpoint; orders that never settled exist
 * only as exceptions, and are shown with the reason rather than left out of the table.
 */
function buildRows(matches: MatchView[], exceptions: ExceptionView[]): Row[] {
  const exceptionsByRef = new Map(exceptions.map((exception) => [exception.entityRef, exception]))
  const rows: Row[] = []
  const seen = new Set<string>()

  for (const match of matches) {
    if (!match.orderId) continue
    seen.add(match.orderId)
    const exception = exceptionsByRef.get(match.orderId)
    rows.push({
      orderId: match.orderId,
      amount: match.amount,
      method: match.method,
      status: exception?.status ?? 'MATCHED',
      settledOn: match.settledOn,
      utr: match.utr,
      confidence: exception?.confidence ?? null,
      match,
      exception,
    })
  }

  for (const exception of exceptions) {
    if (seen.has(exception.entityRef)) continue
    rows.push({
      orderId: exception.entityRef,
      amount: exception.amount,
      method: exception.method,
      status: exception.status,
      settledOn: null,
      utr: exception.entityRef.startsWith('UTR') ? exception.entityRef : null,
      confidence: exception.confidence,
      exception,
    })
  }

  return rows
}
