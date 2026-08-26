import { api } from '../api/client'

/**
 * One narration per batch, started by Reconcile and read by the Waterfall.
 *
 * <p>Kept here rather than in either screen because Reconcile writes it and the Waterfall reads it.
 * A batch is only ever requested once — a failure stays cached as a failure, so a screen that opens
 * afterwards reports it instead of quietly spending a second call on the same numbers.
 */
const inFlight = new Map<string, Promise<string>>()

/** Starts the call and returns immediately; the Waterfall picks up the result whenever it lands. */
export function prefetchNarrative(batchId: string): void {
  if (inFlight.has(batchId)) return
  const request = api.narrative(batchId).then((response) => response.narrative)
  // Nobody is awaiting this yet, and an unwatched rejection must not surface as a page error.
  request.catch(() => {})
  inFlight.set(batchId, request)
}

/** Read-only: returns what Reconcile started, or null if it never did. Never begins a call. */
export function pendingNarrative(batchId: string): Promise<string> | null {
  return inFlight.get(batchId) ?? null
}

/** The one path that may spend a second call, and only because someone asked for it. */
export function retryNarrative(batchId: string): Promise<string> {
  inFlight.delete(batchId)
  prefetchNarrative(batchId)
  return inFlight.get(batchId)!
}
