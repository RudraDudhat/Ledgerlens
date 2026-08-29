import { useEffect, useMemo, useRef, useState } from 'react'

/** Motion shows where a thing came from. Nothing here bounces and nothing loops. */
export const pageTransition = {
  initial: { opacity: 0, y: 4 },
  animate: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: -4 },
  transition: { duration: 0.15, ease: 'easeOut' as const },
}

export const drawerTransition = {
  initial: { x: '100%' },
  animate: { x: 0 },
  exit: { x: '100%' },
  transition: { duration: 0.2, ease: 'easeOut' as const },
}

export const BAR_STAGGER_SECONDS = 0.06

export function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(
    () => typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  )
  useEffect(() => {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)')
    const listener = (event: MediaQueryListEvent) => setReduced(event.matches)
    query.addEventListener('change', listener)
    return () => query.removeEventListener('change', listener)
  }, [])
  return reduced
}

/**
 * Counts up once, on first mount only. Re-rendering a stat because a filter changed should not
 * replay the animation — the number did not arrive again.
 */
export function useCountUp(target: number, durationMs = 700): number {
  const reduced = usePrefersReducedMotion()
  const [value, setValue] = useState(reduced ? target : 0)
  const hasRun = useRef(false)

  useEffect(() => {
    if (hasRun.current || reduced) {
      setValue(target)
      return
    }
    hasRun.current = true
    const started = performance.now()
    let frame = 0
    const step = (now: number) => {
      const progress = Math.min(1, (now - started) / durationMs)
      const eased = 1 - Math.pow(1 - progress, 3)
      setValue(target * eased)
      if (progress < 1) frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    return () => cancelAnimationFrame(frame)
  }, [target, durationMs, reduced])

  return value
}

/**
 * Paces already-arrived text out one word at a time.
 *
 * <p>The narration lands from the backend as one finished string; dropping a whole paragraph in at
 * once reads as a page that was always there. Revealing it word by word shows that a model wrote it,
 * and shows it arriving now. Nothing is fetched here — this is presentation only.
 */
export function useTypewriter(text: string, animate = true, wordsPerSecond = 14): { shown: string; done: boolean } {
  const reduced = usePrefersReducedMotion()
  // Trailing whitespace rides along with its word, so joining the slice rebuilds the text exactly.
  const words = useMemo(() => text.match(/\S+\s*/g) ?? [], [text])
  const [count, setCount] = useState(0)

  useEffect(() => {
    if (!animate || reduced || words.length === 0) {
      setCount(words.length)
      return
    }
    setCount(0)
    const started = performance.now()
    let frame = 0
    const step = (now: number) => {
      const revealed = Math.min(words.length, Math.floor(((now - started) / 1000) * wordsPerSecond))
      setCount(revealed)
      if (revealed < words.length) frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    return () => cancelAnimationFrame(frame)
  }, [words, animate, reduced, wordsPerSecond])

  return { shown: words.slice(0, count).join(''), done: count >= words.length }
}
