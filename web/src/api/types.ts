/**
 * 后端接口类型定义。
 * 字段名严格照 docs/api-contract.md，不做重命名、不做多余包装。
 *
 * 契约里没有写明结构的少数几处（KnowledgeEntry、/api/ai/models 的元素、
 * health.running 的元素、ChatSession/ChatMessage）在下方标注了「契约未写明」，
 * 这些地方在 UI 侧都做了宽松解析，改字段只需要改这一个文件。
 */

export interface ApiErrorBody {
  code?: string
  message?: string
  details?: Record<string, unknown> | null
  traceId?: string
}

/* ---------- 1. 健康与运行态 ---------- */

export interface HealthHook {
  registered: boolean
  error?: string | null
}

export interface HealthOllama {
  baseUrl: string
  reachable: boolean
  models?: string[]
}

/** 契约未写明 running 的元素形态：可能是 ActiveRun，也可能只是脚本名或 id。 */
export type HealthRunningEntry = ActiveRun | string

export interface Health {
  version: string
  authRequired: boolean
  hook: HealthHook
  ollama: HealthOllama
  running: HealthRunningEntry[]
}

/** UI 侧统一后的运行条目。startedAt 可能是 epoch millis（后端）或 ISO 字符串。 */
export interface RunningScript {
  scriptId: string
  label: string
  startedAt: string | number | null
}

/* ---------- 2. 脚本库 ---------- */

export type ScriptStatus = 'ENABLED' | 'DISABLED'

export interface Script {
  id: string
  name: string
  description?: string | null
  source: string
  rev: number
  status: ScriptStatus
  createdAt: string
  updatedAt: string
}

export interface ScriptListResponse {
  items: Script[]
}

export interface ScriptCreateRequest {
  name: string
  description?: string
  source?: string
}

/** PUT /api/scripts/{id}：rev 是乐观锁，过期返回 409 REV_CONFLICT。 */
export interface ScriptUpdateRequest {
  name?: string
  description?: string
  source?: string
  rev: number
  /** 契约未在请求体里列出 status；启用/禁用只能走这个接口，故一并提交。 */
  status?: ScriptStatus
}

export interface ScriptImportRequest {
  filename: string
  content: string
}

export interface ScriptDiagnostic {
  line: number
  column: number
  message: string
}

export interface ValidateResponse {
  ok: boolean
  diagnostics: ScriptDiagnostic[]
  /** 后端额外返回：脚本里是否定义了 OnEvent。契约未列，可选。 */
  hasOnEvent?: boolean
}

/* ---------- 3. 运行控制 ---------- */

export interface RunStartRequest {
  trigger?: string
}

export interface RunStartResponse {
  runId: string
  scriptId: string
  status: 'RUNNING'
}

export interface StopResponse {
  stopped: boolean
}

export interface StopAllResponse {
  stopped: string[]
}

export interface ActiveRun {
  scriptId: string
  /** 后端额外返回脚本名，用于状态栏与「另有 N 个运行中」的提示。 */
  scriptName?: string
  runId: string
  trigger: string
  /** 后端是 epoch millis（long），不是 ISO 字符串。 */
  startedAt: number
  eventCount: number
  actionCount: number
  droppedEvents: number
}

export type RunExit = 'OK' | 'LUA_ERROR' | 'STOPPED' | 'TIMEOUT' | 'KILLED'

/**
 * GET /api/runs 的历史记录，按后端 domain.RunRecord：
 * {id, scriptId, trigger, startedAt(ISO), endedAt(ISO), exit, errorMessage, eventCount, ...}
 * 注意它不是 ActiveRun 的超集：主键叫 id 而不是 runId，没有 scriptName/actionCount/droppedEvents。
 */
export interface RunRecord {
  id: string
  scriptId: string
  trigger: string
  /** ISO 字符串（Instant）。 */
  startedAt: string
  endedAt?: string | null
  exit: RunExit
  errorMessage?: string | null
  eventCount: number
  actionCount?: number
}

export interface ActiveRunListResponse {
  items: ActiveRun[]
}

export interface RunRecordListResponse {
  items: RunRecord[]
}

/* ---------- 4. 热键绑定 ---------- */

export type HotkeyEvent = 'KEY_PRESSED' | 'KEY_RELEASED'
export type HotkeyAction = 'RUN' | 'STOP' | 'TOGGLE'

export interface Hotkey {
  accelerator: string
  event: HotkeyEvent
  action: HotkeyAction
  enabled: boolean
}

export interface HotkeyListResponse {
  items: Hotkey[]
}

export interface HotkeyPutRequest {
  items: Hotkey[]
}

/* ---------- 5. 日志 ---------- */

export type LogLevel = 'DEBUG' | 'INFO' | 'WARN' | 'ERROR'

export interface LogEntry {
  seq: number
  timeMillis: number
  level: LogLevel
  source: string
  scriptId?: string | null
  runId?: string | null
  message: string
}

export interface LogListResponse {
  items: LogEntry[]
}

/* ---------- 6. AI 对话 ---------- */

export interface AiProvider {
  id: string
  kind: string
  baseUrl: string
  model: string
  available: boolean
}

export interface AiProviderListResponse {
  items: AiProvider[]
}

/** 契约未写明 models 的元素形态（字符串或对象都可能），统一在这里兼容。 */
export type AiModelRef = string | { name?: string; id?: string; model?: string }

export interface AiModelListResponse {
  models: AiModelRef[]
}

export interface ChatRequest {
  sessionId?: string
  message: string
  providerId: string
  model?: string
  scriptId?: string
  includeScript?: boolean
  kbEntryIds?: string[]
}

export interface ChatStartedEvent {
  sessionId: string
  messageId: string
  providerId: string
  model: string
}

export type ChatDeltaChannel = 'thinking' | 'content'

export interface ChatDeltaEvent {
  channel: ChatDeltaChannel | string
  text: string
}

export interface ChatUsageEvent {
  tokensIn: number
  tokensOut: number
}

export interface ChatDoneEvent {
  messageId: string
  codeBlocks?: string[]
}

export interface ChatErrorEvent {
  code: string
  message: string
  details?: Record<string, unknown> | null
}

export interface ChatStopRequest {
  sessionId: string
}

/** 后端实际返回：{id,title,providerId,model,createdAt}。 */
export interface ChatSession {
  id: string
  title?: string | null
  providerId?: string | null
  model?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface ChatSessionListResponse {
  items: ChatSession[]
}

/**
 * 后端实际返回：{id,sessionId,role,content,thinking,tokensIn,tokensOut,providerId,model,createdAt}。
 * 思考链单独放在 thinking 字段，不是 channel=thinking 的独立消息。
 */
export interface ChatMessage {
  id?: string
  sessionId?: string
  role?: 'SYSTEM' | 'USER' | 'ASSISTANT' | string
  content?: string
  thinking?: string | null
  tokensIn?: number
  tokensOut?: number
  providerId?: string | null
  model?: string | null
  createdAt?: string
}

export interface ChatMessageListResponse {
  items: ChatMessage[]
}

/* ---------- 7. 知识库 ---------- */

/**
 * 知识库条目。字段按后端 domain.KnowledgeEntry 实现：
 * {id, game, title, description, script, tags, source, enabled, updatedAt}。
 * Lua 正文在 `script` 里（不是 `content`）。
 * ⚠ 契约第 7 节只写了「POST /api/kb 送 KnowledgeEntry（无 id）」，
 *   没有给字段清单，也没提 game/description/enabled —— 以后端实现为准。
 * ⚠ 后端 KnowledgeEntry 的紧凑构造器要求 id 非空，而 POST 请求体必须「无 id」。
 *   若 POST /api/kb 返回 500/400，需要后端放宽这条校验。
 */
export interface KnowledgeEntry {
  id: string
  game?: string | null
  title: string
  description?: string | null
  /** Lua 正文。 */
  script: string
  tags?: string[] | null
  source?: string | null
  enabled?: boolean
  updatedAt?: string | null
}

export interface KnowledgeEntryInput {
  game?: string
  title: string
  description?: string
  script: string
  tags?: string[]
  source?: string | null
  enabled?: boolean
}

export interface KnowledgeEntryListResponse {
  items: KnowledgeEntry[]
}

export type KbSearchMode = 'keyword' | 'auto'

export interface KbSearchRequest {
  query: string
  topK?: number
  mode?: KbSearchMode
}

export interface KbSearchHit {
  entry: KnowledgeEntry
  score: number
  snippet: string
}

export interface KbSearchResponse {
  hits: KbSearchHit[]
}

/* ---------- 8. 设置与 Lua API 文档 ---------- */

/**
 * 后端 domain.Settings 的实际字段。
 * ⚠ 契约第 8 节写了 `cloudModelName`，但后端没有这个字段（只有 cloudModel）。
 *   前端已按后端实现对齐，改回契约需要后端补字段。
 * apiKey 只进不出：GET 只回 hasCloudApiKey。
 */
export interface Settings {
  providerId: string
  ollamaBaseUrl: string
  ollamaModel: string
  cloudBaseUrl: string
  cloudModel: string
  hasCloudApiKey: boolean
  preciseRelativeMove: boolean
  eventQueueCapacity: number
  stopGraceMs: number
  emergencyHotkey: string
}

/**
 * PUT 时可传 cloudApiKey：
 * 后端语义是「null 表示不改动已存密钥，空串表示清除」，
 * 所以只有用户这次真的填了值才带上这个字段。
 */
export interface SettingsUpdate extends Omit<Settings, 'hasCloudApiKey'> {
  cloudApiKey?: string
}

/** 契约未写明 keys/events/mouseButtons 的元素形态，统一兼容字符串与对象。 */
export type LuaApiItem = string | { name?: string; signature?: string; doc?: string }

export interface LuaApiFunction {
  name: string
  signature: string
  doc: string
}

export interface LuaApiDoc {
  functions: LuaApiFunction[]
  keys: LuaApiItem[]
  events: LuaApiItem[]
  mouseButtons: LuaApiItem[]
}
