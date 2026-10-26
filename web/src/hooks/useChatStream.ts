import { useCallback, useEffect, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import { describeError, nextStepFor, type ErrorDescription } from '../api/client'
import { openSse, parseSseData } from '../api/sse'
import type {
  ChatDeltaEvent,
  ChatDoneEvent,
  ChatErrorEvent,
  ChatMessage,
  ChatStartedEvent,
  ChatUsageEvent,
} from '../api/types'

export type TurnStatus = 'streaming' | 'done' | 'error' | 'stopped'

export interface ChatTurn {
  id: string
  role: 'user' | 'assistant'
  content: string
  thinking: string
  status: TurnStatus
  model?: string
  usage?: { tokensIn: number; tokensOut: number }
  codeBlocks?: string[]
  error?: ErrorDescription
}

export interface SendOptions {
  message: string
  providerId: string
  model?: string
  scriptId?: string
  includeScript?: boolean
  kbEntryIds?: string[]
}

let idSeq = 0
function newId(prefix: string): string {
  idSeq += 1
  return `${prefix}-${Date.now()}-${idSeq}`
}

/**
 * 后端 ChatMessage：{role: SYSTEM|USER|ASSISTANT, content, thinking, tokensIn, tokensOut}。
 * 思考链存在独立的 thinking 字段里，不是 channel=thinking 的另一条消息。
 */
function turnFromMessage(message: ChatMessage, index: number): ChatTurn | null {
  const role = (message.role ?? '').toUpperCase()
  const content = message.content ?? ''
  if (role === 'USER') {
    return { id: message.id ?? `hist-user-${index}`, role: 'user', content, thinking: '', status: 'done' }
  }
  if (role === 'ASSISTANT') {
    return {
      id: message.id ?? `hist-assistant-${index}`,
      role: 'assistant',
      content,
      thinking: message.thinking ?? '',
      status: 'done',
      model: message.model ?? undefined,
      usage:
        message.tokensIn !== undefined || message.tokensOut !== undefined
          ? { tokensIn: message.tokensIn ?? 0, tokensOut: message.tokensOut ?? 0 }
          : undefined,
    }
  }
  // SYSTEM 与未知角色不渲染
  return null
}

export interface ChatStream {
  turns: ChatTurn[]
  streaming: boolean
  sessionId: string | null
  send: (options: SendOptions) => Promise<void>
  stop: () => Promise<{ ok: boolean; error?: ErrorDescription }>
  loadHistory: (sessionId: string) => Promise<{ ok: boolean; error?: ErrorDescription }>
  reset: () => void
}

export function useChatStream(): ChatStream {
  const [turns, setTurns] = useState<ChatTurn[]>([])
  const [streaming, setStreaming] = useState(false)
  const [sessionId, setSessionId] = useState<string | null>(null)

  const sessionIdRef = useRef<string | null>(null)
  const streamingRef = useRef(false)
  const abortRef = useRef<AbortController | null>(null)
  const stopTimerRef = useRef<number | null>(null)
  const queryClient = useQueryClient()

  useEffect(() => {
    return () => {
      abortRef.current?.abort()
      if (stopTimerRef.current !== null) window.clearTimeout(stopTimerRef.current)
    }
  }, [])

  const patchTurn = useCallback((id: string, patch: (turn: ChatTurn) => Partial<ChatTurn>) => {
    setTurns((prev) => prev.map((turn) => (turn.id === id ? { ...turn, ...patch(turn) } : turn)))
  }, [])

  const send = useCallback(
    async (options: SendOptions) => {
      const text = options.message.trim()
      if (!text || streamingRef.current) return

      const userTurnId = newId('user')
      const assistantTurnId = newId('assistant')
      setTurns((prev) => [
        ...prev,
        { id: userTurnId, role: 'user', content: text, thinking: '', status: 'done' },
        { id: assistantTurnId, role: 'assistant', content: '', thinking: '', status: 'streaming' },
      ])
      streamingRef.current = true
      setStreaming(true)

      const controller = new AbortController()
      abortRef.current = controller

      try {
        await openSse({
          path: api.ai.chatPath,
          method: 'POST',
          body: {
            sessionId: sessionIdRef.current ?? undefined,
            message: text,
            providerId: options.providerId,
            model: options.model,
            scriptId: options.scriptId,
            includeScript: options.includeScript,
            kbEntryIds: options.kbEntryIds,
          },
          signal: controller.signal,
          connectTimeoutMs: 20_000,
          onMessage: (sseMessage) => {
            switch (sseMessage.event) {
              case 'started': {
                const data = parseSseData<ChatStartedEvent>(sseMessage)
                sessionIdRef.current = data.sessionId
                setSessionId(data.sessionId)
                patchTurn(assistantTurnId, () => ({ model: data.model }))
                break
              }
              case 'delta': {
                const data = parseSseData<ChatDeltaEvent>(sseMessage)
                const chunk = data.text ?? ''
                if (data.channel === 'thinking') {
                  patchTurn(assistantTurnId, (turn) => ({ thinking: turn.thinking + chunk }))
                } else {
                  patchTurn(assistantTurnId, (turn) => ({ content: turn.content + chunk }))
                }
                break
              }
              case 'usage': {
                const data = parseSseData<ChatUsageEvent>(sseMessage)
                patchTurn(assistantTurnId, () => ({
                  usage: { tokensIn: data.tokensIn ?? 0, tokensOut: data.tokensOut ?? 0 },
                }))
                break
              }
              case 'done': {
                const data = parseSseData<ChatDoneEvent>(sseMessage)
                patchTurn(assistantTurnId, () => ({
                  status: 'done',
                  codeBlocks: Array.isArray(data.codeBlocks) ? data.codeBlocks : undefined,
                }))
                break
              }
              case 'error': {
                const data = parseSseData<ChatErrorEvent>(sseMessage)
                patchTurn(assistantTurnId, () => ({
                  status: 'error',
                  error: {
                    code: data.code,
                    reason: data.message || 'AI 服务返回了错误。',
                    nextStep: nextStepFor(data.code),
                    traceId: null,
                  },
                }))
                break
              }
              default:
                break
            }
          },
        })
        patchTurn(assistantTurnId, (turn) => (turn.status === 'streaming' ? { status: 'done' } : {}))
      } catch (error) {
        if (controller.signal.aborted) {
          patchTurn(assistantTurnId, () => ({ status: 'stopped' }))
        } else {
          patchTurn(assistantTurnId, () => ({ status: 'error', error: describeError(error) }))
        }
      } finally {
        abortRef.current = null
        streamingRef.current = false
        setStreaming(false)
        void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] })
      }
    },
    [patchTurn, queryClient],
  )

  const stop = useCallback(async (): Promise<{ ok: boolean; error?: ErrorDescription }> => {
    const target = sessionIdRef.current
    if (!target) {
      return { ok: false, error: { code: 'NO_SESSION', reason: '本次回复还没有拿到会话 id，无法取消。', nextStep: '等 started 事件到达后再停止，或直接关闭页面。', traceId: null } }
    }
    try {
      const result = await api.ai.stopChat({ sessionId: target })
      // 后端取消后流会自己收尾；5 秒还没结束就本地断开，避免按钮一直转。
      stopTimerRef.current = window.setTimeout(() => {
        abortRef.current?.abort()
      }, 5000)
      if (!result?.stopped) {
        return {
          ok: false,
          error: {
            code: 'NOT_STOPPED',
            reason: '后端报告没有正在进行的请求。',
            nextStep: '可能刚好已经结束，等一会儿看回复是否补全。',
            traceId: null,
          },
        }
      }
      return { ok: true }
    } catch (error) {
      return { ok: false, error: describeError(error) }
    }
  }, [])

  const loadHistory = useCallback(
    async (target: string): Promise<{ ok: boolean; error?: ErrorDescription }> => {
      try {
        const response = await api.ai.messages(target)
        const items = Array.isArray(response?.items) ? response.items : []
        const mapped = items
          .map((message, index) => turnFromMessage(message, index))
          .filter((turn): turn is ChatTurn => turn !== null)
        sessionIdRef.current = target
        setSessionId(target)
        setTurns(mapped)
        return { ok: true }
      } catch (error) {
        return { ok: false, error: describeError(error) }
      }
    },
    [],
  )

  const reset = useCallback(() => {
    abortRef.current?.abort()
    sessionIdRef.current = null
    setSessionId(null)
    setTurns([])
  }, [])

  return { turns, streaming, sessionId, send, stop, loadHistory, reset }
}
