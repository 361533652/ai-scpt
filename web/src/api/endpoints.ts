/**
 * 契约里的每个接口在这里各有一个函数，UI 不直接拼路径。
 * 路径与 docs/api-contract.md 一一对应。
 */
import { apiRequest, apiText, type QueryValue } from './client'
import type {
  ActiveRunListResponse,
  AiModelListResponse,
  AiProviderListResponse,
  ChatMessageListResponse,
  ChatSessionListResponse,
  ChatStopRequest,
  Health,
  Hotkey,
  HotkeyListResponse,
  KbSearchRequest,
  KbSearchResponse,
  KnowledgeEntry,
  KnowledgeEntryInput,
  KnowledgeEntryListResponse,
  LogEntry,
  LogLevel,
  LogListResponse,
  LuaApiDoc,
  RunRecordListResponse,
  RunStartRequest,
  RunStartResponse,
  Script,
  ScriptCreateRequest,
  ScriptImportRequest,
  ScriptListResponse,
  ScriptStatus,
  ScriptUpdateRequest,
  Settings,
  SettingsUpdate,
  StopAllResponse,
  StopResponse,
  ValidateResponse,
} from './types'

export const api = {
  health: () => apiRequest<Health>('/health'),

  scripts: {
    list: () => apiRequest<ScriptListResponse>('/scripts'),
    get: (id: string) => apiRequest<Script>(`/scripts/${encodeURIComponent(id)}`),
    create: (body: ScriptCreateRequest) => apiRequest<Script>('/scripts', { method: 'POST', body }),
    update: (id: string, body: ScriptUpdateRequest) =>
      apiRequest<Script>(`/scripts/${encodeURIComponent(id)}`, { method: 'PUT', body }),
    remove: (id: string) => apiRequest<void>(`/scripts/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    /**
     * 启用/禁用。
     * ⚠ 契约第 2 节没有这个接口，但后端实现了 PUT /api/scripts/{id}/status（body {status}）。
     *   前端按后端实现对接；如果坚持只走契约，需要后端把 status 合并进 PUT /api/scripts/{id}。
     */
    setStatus: (id: string, status: ScriptStatus) =>
      apiRequest<Script>(`/scripts/${encodeURIComponent(id)}/status`, {
        method: 'PUT',
        body: { status },
      }),
    validate: (id: string, source?: string) =>
      apiRequest<ValidateResponse>(`/scripts/${encodeURIComponent(id)}/validate`, {
        method: 'POST',
        body: source === undefined ? {} : { source },
      }),
    importLua: (body: ScriptImportRequest) => apiRequest<Script>('/scripts/import', { method: 'POST', body }),
    exportText: (id: string) => apiText(`/scripts/${encodeURIComponent(id)}/export`),
    run: (id: string, body: RunStartRequest = {}) =>
      apiRequest<RunStartResponse>(`/scripts/${encodeURIComponent(id)}/run`, { method: 'POST', body }),
    stop: (id: string) =>
      apiRequest<StopResponse>(`/scripts/${encodeURIComponent(id)}/stop`, { method: 'POST' }),
    hotkeys: (id: string) =>
      apiRequest<HotkeyListResponse>(`/scripts/${encodeURIComponent(id)}/hotkeys`),
    putHotkeys: (id: string, items: Hotkey[]) =>
      apiRequest<HotkeyListResponse>(`/scripts/${encodeURIComponent(id)}/hotkeys`, {
        method: 'PUT',
        body: { items },
      }),
  },

  runs: {
    active: () => apiRequest<ActiveRunListResponse>('/runs/active'),
    list: (scriptId?: string, limit?: number) =>
      apiRequest<RunRecordListResponse>('/runs', {
        query: { scriptId, limit } as Record<string, QueryValue>,
      }),
    stopAll: () => apiRequest<StopAllResponse>('/runs/stop-all', { method: 'POST' }),
  },

  logs: {
    list: (query: { scriptId?: string; level?: LogLevel; limit?: number; sinceSeq?: number }) =>
      apiRequest<LogListResponse>('/logs', { query: query as Record<string, QueryValue> }),
  },

  ai: {
    chatPath: '/ai/chat',
    providers: () => apiRequest<AiProviderListResponse>('/ai/providers'),
    models: (providerId: string) =>
      apiRequest<AiModelListResponse>('/ai/models', { query: { providerId } }),
    sessions: () => apiRequest<ChatSessionListResponse>('/ai/sessions'),
    messages: (sessionId: string) =>
      apiRequest<ChatMessageListResponse>(`/ai/sessions/${encodeURIComponent(sessionId)}/messages`),
    deleteSession: (sessionId: string) =>
      apiRequest<void>(`/ai/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' }),
    stopChat: (body: ChatStopRequest) => apiRequest<StopResponse>('/ai/chat/stop', { method: 'POST', body }),
  },

  kb: {
    list: (q?: string, limit?: number) =>
      apiRequest<KnowledgeEntryListResponse>('/kb', { query: { q, limit } as Record<string, QueryValue> }),
    create: (body: KnowledgeEntryInput) => apiRequest<KnowledgeEntry>('/kb', { method: 'POST', body }),
    update: (id: string, body: KnowledgeEntryInput) =>
      apiRequest<KnowledgeEntry>(`/kb/${encodeURIComponent(id)}`, { method: 'PUT', body }),
    remove: (id: string) => apiRequest<void>(`/kb/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    search: (body: KbSearchRequest) => apiRequest<KbSearchResponse>('/kb/search', { method: 'POST', body }),
  },

  settings: {
    get: () => apiRequest<Settings>('/settings'),
    put: (body: SettingsUpdate) => apiRequest<Settings>('/settings', { method: 'PUT', body }),
  },

  luaApi: () => apiRequest<LuaApiDoc>('/lua/api'),
}

export type Api = typeof api

export type { LogEntry }
