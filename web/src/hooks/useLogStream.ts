import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import { describeError } from '../api/client'
import { openSse, parseSseData } from '../api/sse'
import type { LogEntry } from '../api/types'

const MAX_ENTRIES = 3000

export type LogStreamStatus = 'connecting' | 'open' | 'retry' | 'stopped'

export interface LogStream {
  entries: LogEntry[]
  status: LogStreamStatus
  detail: string
  paused: boolean
  pendingCount: number
  setPaused: (paused: boolean) => void
  clear: () => void
}

/**
 * /api/logs/stream 的读取：先用 REST 补一段历史，再挂 SSE 增量。
 * 断线按 1s→2s→4s→8s→10s 重连，带 sinceSeq 续传，seq 去重。
 */
export function useLogStream(enabled: boolean): LogStream {
  const [entries, setEntries] = useState<LogEntry[]>([])
  const [status, setStatus] = useState<LogStreamStatus>('connecting')
  const [detail, setDetail] = useState('')
  const [paused, setPausedState] = useState(false)
  const [pendingCount, setPendingCount] = useState(0)

  const lastSeqRef = useRef(0)
  const pausedRef = useRef(false)
  const pendingRef = useRef<LogEntry[]>([])
  const statusRef = useRef<LogStreamStatus>('connecting')
  const queryClient = useQueryClient()

  const setStatusSafe = useCallback((next: LogStreamStatus, message = '') => {
    statusRef.current = next
    setStatus(next)
    setDetail(message)
  }, [])

  const append = useCallback((incoming: LogEntry[]) => {
    setEntries((prev) => {
      const next = prev.concat(incoming)
      return next.length > MAX_ENTRIES ? next.slice(next.length - MAX_ENTRIES) : next
    })
  }, [])

  const push = useCallback(
    (entry: LogEntry) => {
      if (Number.isFinite(entry.seq)) {
        if (entry.seq <= lastSeqRef.current) return
        lastSeqRef.current = entry.seq
      }
      if (pausedRef.current) {
        const pending = pendingRef.current
        pending.push(entry)
        if (pending.length > MAX_ENTRIES) pending.splice(0, pending.length - MAX_ENTRIES)
        setPendingCount(pending.length)
        return
      }
      append([entry])
    },
    [append],
  )

  const setPaused = useCallback(
    (value: boolean) => {
      pausedRef.current = value
      setPausedState(value)
      if (!value && pendingRef.current.length > 0) {
        const flushed = pendingRef.current
        pendingRef.current = []
        setPendingCount(0)
        append(flushed)
      }
    },
    [append],
  )

  const clear = useCallback(() => {
    pendingRef.current = []
    setPendingCount(0)
    setEntries([])
  }, [])

  useEffect(() => {
    if (!enabled) {
      setStatusSafe('stopped')
      return
    }

    let cancelled = false
    const controller = new AbortController()
    let retryDelay = 1000

    const backfill = async () => {
      try {
        const history = await api.logs.list({ limit: 500 })
        const items = Array.isArray(history?.items) ? history.items : []
        if (cancelled || items.length === 0) return
        const maxSeq = items.reduce((max, item) => (item.seq > max ? item.seq : max), lastSeqRef.current)
        lastSeqRef.current = maxSeq
        append(items)
      } catch {
        // 历史日志取不到不影响实时流，SSE 建连时的错误会单独提示。
      }
    }

    const loop = async () => {
      await backfill()
      while (!cancelled) {
        try {
          setStatusSafe('connecting')
          await openSse({
            path: '/logs/stream',
            query: { sinceSeq: lastSeqRef.current || undefined },
            signal: controller.signal,
            onComment: () => {
              if (statusRef.current !== 'open') setStatusSafe('open')
            },
            onMessage: (message) => {
              if (statusRef.current !== 'open') setStatusSafe('open')
              if (message.event === 'log') {
                push(parseSseData<LogEntry>(message))
                return
              }
              if (message.event === 'run') {
                void queryClient.invalidateQueries({ queryKey: ['health'] })
                return
              }
            },
          })
          if (cancelled) return
          retryDelay = 1000
          setStatusSafe('retry', '日志流已断开，正在重连。')
        } catch (error) {
          if (cancelled || controller.signal.aborted) return
          const described = describeError(error)
          retryDelay = Math.min(retryDelay * 2, 10_000)
          setStatusSafe('retry', `${described.reason}${described.nextStep ? ` ${described.nextStep}` : ''}`)
        }
        await new Promise((resolve) => window.setTimeout(resolve, retryDelay))
      }
    }

    void loop()
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [enabled, append, push, queryClient, setStatusSafe])

  return { entries, status, detail, paused, pendingCount, setPaused, clear }
}
