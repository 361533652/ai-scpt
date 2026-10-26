/**
 * 手写的 SSE 读取。
 * 不用 EventSource：POST 要请求体、要自定义头（X-GmScript-Token），EventSource 都做不到。
 * 兼容 `: ping` 注释行、`id:` 断线续传序号、多行 data。
 */
import { authHeaders, buildUrl, toApiError, type QueryValue } from './client'

export interface SseMessage {
  event: string
  data: string
  id: string | null
}

export interface SseOptions {
  path: string
  method?: 'GET' | 'POST'
  body?: unknown
  query?: Record<string, QueryValue>
  signal?: AbortSignal
  /** 建连阶段（拿到响应头之前）的超时。流开始后不再限时。 */
  connectTimeoutMs?: number
  onMessage: (message: SseMessage) => void
  /** 收到 `: ping` 之类的注释行。 */
  onComment?: (text: string) => void
  /** 流正常结束（服务端关闭）。 */
  onClose?: () => void
}

interface PendingEvent {
  event: string | null
  data: string[]
  id: string | null
}

function createPending(): PendingEvent {
  return { event: null, data: [], id: null }
}

function dispatch(pending: PendingEvent, onMessage: (m: SseMessage) => void): void {
  if (pending.data.length === 0 && pending.event === null) return
  onMessage({
    event: pending.event ?? 'message',
    data: pending.data.join('\n'),
    id: pending.id,
  })
}

export async function openSse(options: SseOptions): Promise<void> {
  const {
    path,
    method = 'GET',
    body,
    query,
    signal,
    connectTimeoutMs = 15_000,
    onMessage,
    onComment,
    onClose,
  } = options

  const headers = authHeaders({
    Accept: 'text/event-stream',
    'Cache-Control': 'no-cache',
  })
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const controller = new AbortController()
  let connectTimedOut = false
  const connectTimer = window.setTimeout(() => {
    connectTimedOut = true
    controller.abort()
  }, connectTimeoutMs)
  const abortFromCaller = () => controller.abort()
  signal?.addEventListener('abort', abortFromCaller)

  let response: Response
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      credentials: 'omit',
      cache: 'no-store',
    })
  } catch (error) {
    if (signal?.aborted) throw error
    if (connectTimedOut) {
      throw new Error(`连接 ${path} 超时。确认后端已启动且接口可用。`)
    }
    throw error
  } finally {
    window.clearTimeout(connectTimer)
  }

  if (!response.ok) {
    signal?.removeEventListener('abort', abortFromCaller)
    throw await toApiError(response)
  }
  if (!response.body) {
    signal?.removeEventListener('abort', abortFromCaller)
    throw new Error('后端没有返回流式响应体，无法读取事件。')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let pending = createPending()

  const handleLine = (rawLine: string) => {
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
    if (line === '') {
      dispatch(pending, onMessage)
      pending = createPending()
      return
    }
    if (line.startsWith(':')) {
      onComment?.(line.slice(1).trim())
      return
    }
    const colon = line.indexOf(':')
    const field = colon === -1 ? line : line.slice(0, colon)
    let value = colon === -1 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    switch (field) {
      case 'event':
        pending.event = value
        break
      case 'data':
        pending.data.push(value)
        break
      case 'id':
        pending.id = value
        break
      default:
        break
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let newlineIndex = buffer.indexOf('\n')
      while (newlineIndex !== -1) {
        handleLine(buffer.slice(0, newlineIndex))
        buffer = buffer.slice(newlineIndex + 1)
        newlineIndex = buffer.indexOf('\n')
      }
    }
    buffer += decoder.decode()
    if (buffer.length > 0) {
      for (const line of buffer.split('\n')) handleLine(line)
    }
    dispatch(pending, onMessage)
    if (!signal?.aborted) onClose?.()
  } finally {
    signal?.removeEventListener('abort', abortFromCaller)
    reader.releaseLock?.()
  }
}

/** SSE 的 data 一定是 JSON；解析失败时抛出带原因的错误。 */
export function parseSseData<T>(message: SseMessage): T {
  try {
    return JSON.parse(message.data) as T
  } catch {
    throw new Error(`SSE 事件 ${message.event} 的 data 不是合法 JSON：${message.data.slice(0, 200)}`)
  }
}
