/**
 * 统一请求封装。
 * 职责：拼 URL、带 X-GmScript-Token、超时、把错误体 {code,message,details,traceId}
 * 变成带「原因 + 下一步」的 ApiError。所有业务请求都从这里走。
 */
import { getToken, TOKEN_HEADER } from '../lib/token'
import type { ApiErrorBody } from './types'

export const API_BASE = '/api'
export const DEFAULT_TIMEOUT_MS = 20_000
/** 后端绑定的地址，只用于文案。 */
export const BACKEND_LABEL = '127.0.0.1:8790'

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

export type QueryValue = string | number | boolean | undefined | null

export interface ApiErrorInit {
  code: string
  status: number
  reason: string
  nextStep: string
  details?: Record<string, unknown> | null
  traceId?: string | null
}

export class ApiError extends Error {
  readonly code: string
  readonly status: number
  /** 后端给的 message，或本地生成的原因说明。 */
  readonly reason: string
  /** 给用户看的下一步动作。 */
  readonly nextStep: string
  readonly details: Record<string, unknown> | null
  readonly traceId: string | null

  constructor(init: ApiErrorInit) {
    super(`[${init.code}] ${init.reason}`)
    this.name = 'ApiError'
    this.code = init.code
    this.status = init.status
    this.reason = init.reason
    this.nextStep = init.nextStep
    this.details = init.details ?? null
    this.traceId = init.traceId ?? null
  }
}

export interface ErrorDescription {
  code: string
  reason: string
  nextStep: string
  traceId: string | null
}

/** 错误码 → 下一步。文字按 docs/ui-design.md 第五节：给原因、给下一步。 */
const NEXT_STEP: Record<string, string> = {
  VALIDATION_FAILED: '检查提示里指出的字段后重新提交。',
  SCRIPT_NOT_FOUND: '该脚本可能已被删除，刷新脚本库后重试。',
  REV_CONFLICT: '内容已在别处被修改。先载入最新版本，或用当前内容覆盖保存。',
  ACCEL_CONFLICT: '该热键已被占用。换一个组合，或先解绑占用它的脚本。',
  SCRIPT_ALREADY_RUNNING: '该脚本已经在运行。先停止，再重新运行。',
  LUA_COMPILE_ERROR: '脚本有语法错误。查看编辑器行号上的红点与控制台输出，改完再运行。',
  LLM_OFFLINE: 'AI 服务不可达。确认 Ollama 已启动，或在设置里改地址后重试。',
  LLM_UPSTREAM_ERROR: 'AI 服务返回错误。稍后重试，或在设置里换一个模型。',
  INTERNAL: '后端内部错误。带 traceId 去后端控制台日志里查这次请求。',
  ORIGIN_REJECTED: '请求来源被拒绝。开发期请通过 vite 代理访问，或直接用 127.0.0.1:8790 打开页面。',
  UNAUTHORIZED: 'token 无效或已过期。用后端启动日志里的链接重新打开页面。',
  NETWORK: `无法连接后端 ${BACKEND_LABEL}。确认后端已启动并监听该端口。`,
  TIMEOUT: '后端长时间没有响应。确认后端没有卡住，然后重试。',
  BAD_RESPONSE: '后端返回的内容不是合法 JSON。检查后端日志里这次请求的响应体。',
}

export function nextStepFor(code: string, status = 0): string {
  const known = NEXT_STEP[code]
  if (known) return known
  if (status === 401) return NEXT_STEP.UNAUTHORIZED
  if (status === 403) return NEXT_STEP.ORIGIN_REJECTED
  if (status === 404) return '路径不存在。确认后端版本与 docs/api-contract.md 一致。'
  if (status >= 500) return NEXT_STEP.INTERNAL
  return '稍后重试；若一直失败，带 traceId 查看后端日志。'
}

function defaultReason(body: ApiErrorBody | null, status: number): string {
  const message = body?.message?.trim()
  if (message) return message
  if (status === 401) return '请求未通过鉴权。'
  if (status === 403) return '请求被拒绝。'
  if (status === 404) return '接口或资源不存在。'
  if (status >= 500) return `后端返回 ${status}。`
  return `后端返回 ${status}。`
}

let unauthorizedHandler: (() => void) | null = null

/** App 挂载时注册：401 时把界面切回 token 缺失提示。 */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

/** 把任意异常统一成「原因 + 下一步」，交给 UI 渲染。 */
export function describeError(error: unknown): ErrorDescription {
  if (isApiError(error)) {
    return {
      code: error.code,
      reason: error.reason,
      nextStep: error.nextStep,
      traceId: error.traceId,
    }
  }
  if (error instanceof DOMException && error.name === 'AbortError') {
    return { code: 'ABORTED', reason: '请求已取消。', nextStep: '需要结果的话重新发起一次。', traceId: null }
  }
  if (error instanceof Error) {
    return {
      code: 'UNEXPECTED',
      reason: error.message || '前端出现未预期的异常。',
      nextStep: '重试一次；若仍失败，把这段文字连同浏览器控制台报错一起记录。',
      traceId: null,
    }
  }
  return { code: 'UNKNOWN', reason: '出现未知错误。', nextStep: '重试一次，仍失败则查看浏览器控制台。', traceId: null }
}

function networkError(): ApiError {
  return new ApiError({
    code: 'NETWORK',
    status: 0,
    reason: `无法连接后端 ${BACKEND_LABEL}。`,
    nextStep: NEXT_STEP.NETWORK,
  })
}

function timeoutError(timeoutMs: number): ApiError {
  return new ApiError({
    code: 'TIMEOUT',
    status: 0,
    reason: `后端 ${Math.round(timeoutMs / 1000)} 秒未响应。`,
    nextStep: NEXT_STEP.TIMEOUT,
  })
}

export function buildUrl(path: string, query?: Record<string, QueryValue>): string {
  const url = path.startsWith('/') ? `${API_BASE}${path}` : `${API_BASE}/${path}`
  if (!query) return url
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    params.set(key, String(value))
  }
  const qs = params.toString()
  return qs ? `${url}?${qs}` : url
}

export function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...extra }
  const token = getToken()
  if (token) headers[TOKEN_HEADER] = token
  return headers
}

/** 把非 2xx 响应转成 ApiError（解析错误体，失败则退回状态码）。 */
export async function toApiError(response: Response): Promise<ApiError> {
  let body: ApiErrorBody | null = null
  let text = ''
  try {
    text = await response.text()
  } catch {
    text = ''
  }
  if (text) {
    try {
      body = JSON.parse(text) as ApiErrorBody
    } catch {
      body = null
    }
  }
  const code = body?.code?.trim() || `HTTP_${response.status}`
  if (response.status === 401 && unauthorizedHandler) unauthorizedHandler()
  return new ApiError({
    code,
    status: response.status,
    reason: defaultReason(body, response.status),
    nextStep: nextStepFor(code, response.status),
    details: body?.details ?? null,
    traceId: body?.traceId ?? response.headers.get('X-Trace-Id'),
  })
}

export interface RequestOptions {
  method?: HttpMethod
  body?: unknown
  query?: Record<string, QueryValue>
  signal?: AbortSignal
  timeoutMs?: number
  /** 额外请求头（例如 SSE 的 Accept）。 */
  headers?: Record<string, string>
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, query, signal, timeoutMs = DEFAULT_TIMEOUT_MS, headers: extraHeaders } = options

  const headers = authHeaders(extraHeaders)
  if (!headers['Accept']) headers['Accept'] = 'application/json'
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const controller = new AbortController()
  let timedOut = false
  const timer = window.setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)
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
    // 调用方主动取消：原样抛出，让上层按 AbortError 处理。
    if (signal?.aborted) throw error
    if (timedOut) throw timeoutError(timeoutMs)
    throw networkError()
  } finally {
    window.clearTimeout(timer)
    signal?.removeEventListener('abort', abortFromCaller)
  }

  if (!response.ok) throw await toApiError(response)
  if (response.status === 204) return undefined as T

  const text = await response.text()
  if (!text) return undefined as T

  const contentType = response.headers.get('content-type') ?? ''
  if (contentType.includes('json') || /^\s*[[{]/.test(text)) {
    try {
      return JSON.parse(text) as T
    } catch {
      throw new ApiError({
        code: 'BAD_RESPONSE',
        status: response.status,
        reason: '后端返回的内容不是合法 JSON。',
        nextStep: NEXT_STEP.BAD_RESPONSE,
      })
    }
  }
  return text as unknown as T
}

/** text/plain 接口（脚本导出）。 */
export function apiText(path: string, options: RequestOptions = {}): Promise<string> {
  return apiRequest<string>(path, { ...options, headers: { Accept: 'text/plain', ...options.headers } })
}
