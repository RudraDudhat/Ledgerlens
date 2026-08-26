/**
 * Money is never rounded here. The API sends exact decimal strings and every helper keeps all the
 * paise it was given: a reconciliation that displays 1,23,456.0 when the ledger says 1,23,456.04 is
 * worse than useless.
 */
const INDIAN_GROUPING = new Intl.NumberFormat('en-IN', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
})

export function rupees(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const amount = typeof value === 'string' ? Number(value) : value
  if (!Number.isFinite(amount)) return '—'
  return `₹${INDIAN_GROUPING.format(amount)}`
}

/** Keeps the sign visible, which is the whole point of a waterfall step. */
export function signedRupees(value: number | string): string {
  const amount = typeof value === 'string' ? Number(value) : value
  const formatted = rupees(Math.abs(amount))
  if (amount === 0) return formatted
  return amount > 0 ? `+${formatted}` : `−${formatted}`
}

export function percent(rate: number | string | null | undefined): string {
  if (rate === null || rate === undefined) return '—'
  const value = typeof rate === 'string' ? Number(rate) : rate
  return `${(value * 100).toFixed(2)}%`
}

export function shortDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(`${iso}T00:00:00`)
  return date.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })
}

export function weekday(iso: string): string {
  return new Date(`${iso}T00:00:00`).toLocaleDateString('en-IN', { weekday: 'short' })
}

export function isWeekend(iso: string): boolean {
  const day = new Date(`${iso}T00:00:00`).getDay()
  return day === 0 || day === 6
}

export function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

export function addDays(iso: string, days: number): string {
  const date = new Date(`${iso}T00:00:00`)
  date.setDate(date.getDate() + days)
  return date.toISOString().slice(0, 10)
}
