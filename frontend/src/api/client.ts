/** Types mirror the backend DTOs. Money arrives as JSON numbers with exact decimals; nothing here rounds. */

export type IngestResponse = {
  batchId: string
  orders: number
  payments: number
  disputes: number
  settlementBatches: number
  settlementLines: number
  bankEntries: number
}

export type ReconcileSummary = {
  batchId: string
  orderCount: number
  matchedOrderCount: number
  orderMatchRate: number
  settlementBatchCount: number
  matchedSettlementBatchCount: number
  bankEntryCount: number
  matchedBankEntryCount: number
  matchesByType: Record<string, number>
  countsByStatus: Record<string, number>
  grossSales: number
  totalSettled: number
  totalBankCredits: number
}

export type MatchView = {
  id: number
  matchType: string
  orderId: string | null
  method: string | null
  utr: string | null
  amount: number
  settledOn: string | null
  bankDate: string | null
  bankAmount: number | null
}

export type ExceptionView = {
  id: number
  status: string
  entityRef: string
  reason: string
  confidence: number
  amount: number | null
  method: string | null
  origin: string
  sourceRowIds: number[]
}

export type WaterfallStep = {
  label: string
  amount: number
  sourceRowIds: number[]
}

export type ForecastEntry = {
  date: string
  expectedAmount: number
  breakdownByMethod: Record<string, number>
  heldAmount: number
}

export type AskResponse = { answer: string; citedRowIds: number[] }
export type NarrativeResponse = { narrative: string }

export type Page<T> = { content: T[]; totalElements: number }

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    /** What the user can actually do about it, shown under the message. */
    readonly hint: string,
  ) {
    super(message)
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, init)
  } catch {
    throw new ApiError('Could not reach the backend.', 0, 'Check that the API is running on port 8080.')
  }

  if (!response.ok) {
    const body = await response.text()
    const detail = safeDetail(body) ?? response.statusText
    throw new ApiError(detail, response.status, hintFor(response.status))
  }
  return (await response.json()) as T
}

function safeDetail(body: string): string | null {
  try {
    const parsed = JSON.parse(body) as { detail?: string; message?: string }
    return parsed.detail ?? parsed.message ?? null
  } catch {
    return body.length > 0 && body.length < 300 ? body : null
  }
}

/**
 * The statement comes back as bytes, so it cannot go through request(), which parses JSON. The
 * server names the file; the fallback only covers a proxy that drops the header.
 */
async function download(path: string): Promise<{ blob: Blob; filename: string }> {
  let response: Response
  try {
    response = await fetch(path)
  } catch {
    throw new ApiError('Could not reach the backend.', 0, 'Check that the API is running on port 8080.')
  }

  if (!response.ok) {
    const body = await response.text()
    const detail = safeDetail(body) ?? response.statusText
    throw new ApiError(detail, response.status, hintFor(response.status))
  }

  const named = /filename="([^"]+)"/.exec(response.headers.get('Content-Disposition') ?? '')
  return { blob: await response.blob(), filename: named ? named[1] : 'ledgerlens-statement.pdf' }
}

function hintFor(status: number): string {
  if (status === 404) return 'That batch no longer exists. Upload the three files again.'
  if (status === 409) return 'Run the reconciliation for this batch first.'
  if (status === 503) return 'This feature needs an API key. Set GEMINI_API_KEY and restart the backend.'
  if (status === 400) return 'Check the values you sent and try again.'
  if (status === 502) return 'The model rejected the request. Check GEMINI_API_KEY and the backend logs.'
  return 'Try again, and check the backend logs if it keeps failing.'
}

export const api = {
  ingestCsv(files: { orders: File; settlement: File; bank: File }): Promise<IngestResponse> {
    const form = new FormData()
    form.append('orders', files.orders)
    form.append('settlement', files.settlement)
    form.append('bank', files.bank)
    return request<IngestResponse>('/api/ingest/csv', { method: 'POST', body: form })
  },
  reconcile(batchId: string): Promise<ReconcileSummary> {
    return request<ReconcileSummary>(`/api/reconcile/${batchId}`, { method: 'POST' })
  },
  summary(batchId: string): Promise<ReconcileSummary> {
    return request<ReconcileSummary>(`/api/reconcile/${batchId}/summary`)
  },
  matches(batchId: string, size = 2000): Promise<Page<MatchView>> {
    return request<Page<MatchView>>(`/api/reconcile/${batchId}/matches?size=${size}`)
  },
  exceptions(batchId: string): Promise<ExceptionView[]> {
    return request<ExceptionView[]>(`/api/reconcile/${batchId}/exceptions`)
  },
  waterfall(batchId: string): Promise<WaterfallStep[]> {
    return request<WaterfallStep[]>(`/api/reconcile/${batchId}/waterfall`)
  },
  narrative(batchId: string): Promise<NarrativeResponse> {
    return request<NarrativeResponse>(`/api/reconcile/${batchId}/narrative`)
  },
  statementPdf(batchId: string): Promise<{ blob: Blob; filename: string }> {
    return download(`/api/reconcile/${batchId}/statement.pdf`)
  },
  forecast(batchId: string): Promise<ForecastEntry[]> {
    return request<ForecastEntry[]>(`/api/forecast/${batchId}`)
  },
  ask(batchId: string, question: string): Promise<AskResponse> {
    return request<AskResponse>(`/api/ask/${batchId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question }),
    })
  },
}

/** Pulls the committed 300-row batch that vite serves from the repo's data directory. */
export async function loadSampleFiles(): Promise<{ orders: File; settlement: File; bank: File }> {
  const fetchFile = async (name: string, type: string) => {
    const response = await fetch(`/sample/${name}`)
    if (!response.ok) throw new ApiError('Sample data is not available.', response.status, 'Run the generate profile to create data/.')
    return new File([await response.blob()], name, { type })
  }
  const [orders, settlement, bank] = await Promise.all([
    fetchFile('orders.csv', 'text/csv'),
    fetchFile('razorpay_settlement.csv', 'text/csv'),
    fetchFile('bank_statement.csv', 'text/csv'),
  ])
  return { orders, settlement, bank }
}

export type BatchMetrics = {
  feeRate: number
  failureRate: number
  failureRateByMethod: Record<string, number>
  failureRateByHour: Record<string, number>
  disputeRate: number
  matchRate: number
  settlementDelayDaysByMethod: Record<string, number>
  avgSettlementDelayDays: number
  orderCount: number
}

export type AlertView = {
  id: number
  metric: string
  currentValue: number
  baselineValue: number
  ratio: number
  severity: 'WARN' | 'HIGH'
  sourceRowIds: number[]
  likelyCause: string | null
  suggestedCheck: string | null
  confidence: number | null
}

export type HealthReport = {
  batchId: string
  metrics: BatchMetrics
  baseline: BatchMetrics | null
  priorBatchCount: number
  insufficientHistory: boolean
  alerts: AlertView[]
}

export type HealthHistoryPoint = { batchId: string; computedAt: string; metrics: BatchMetrics }

export const healthApi = {
  report(batchId: string): Promise<HealthReport> {
    return request<HealthReport>(`/api/health/${batchId}`)
  },
  history(batchId: string): Promise<HealthHistoryPoint[]> {
    return request<HealthHistoryPoint[]>(`/api/health/${batchId}/history`)
  },
}
