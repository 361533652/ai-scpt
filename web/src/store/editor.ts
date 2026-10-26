/**
 * 编辑器会话状态：当前脚本、草稿、保存基线、校验诊断、rev 冲突标记。
 * 另外按脚本 id 缓存草稿：切到别的脚本再切回来，未保存的修改不会丢。
 */
import { create } from 'zustand'
import type { Script, ScriptDiagnostic, ScriptStatus, ValidateResponse } from '../api/types'

interface DraftCache {
  draft: string
  base: string
  rev: number
}

interface EditorState {
  scriptId: string | null
  name: string
  description: string
  status: ScriptStatus
  rev: number
  draft: string
  savedSource: string
  dirty: boolean
  diagnostics: ScriptDiagnostic[]
  validateOk: boolean | null
  validatedAt: number | null
  revConflict: boolean
  drafts: Record<string, DraftCache>

  loadScript: (script: Script) => void
  setDraft: (draft: string) => void
  markSaved: (script: Script) => void
  dropDraft: (scriptId: string) => void
  setDiagnostics: (result: ValidateResponse) => void
  clearDiagnostics: () => void
  setRevConflict: (value: boolean) => void
  clear: () => void
}

export const useEditorStore = create<EditorState>((set, get) => ({
  scriptId: null,
  name: '',
  description: '',
  status: 'ENABLED',
  rev: 0,
  draft: '',
  savedSource: '',
  dirty: false,
  diagnostics: [],
  validateOk: null,
  validatedAt: null,
  revConflict: false,
  drafts: {},

  loadScript: (script) => {
    const source = script.source ?? ''
    const cached = get().drafts[script.id]
    // 只有草稿的基线 rev 与服务端一致时才恢复草稿：rev 变了说明别处改过，服务端版本优先。
    const restore = cached !== undefined && cached.rev === script.rev && cached.draft !== cached.base
    set({
      scriptId: script.id,
      name: script.name,
      description: script.description ?? '',
      status: script.status,
      rev: script.rev,
      draft: restore && cached ? cached.draft : source,
      savedSource: restore && cached ? cached.base : source,
      dirty: restore,
      diagnostics: [],
      validateOk: null,
      validatedAt: null,
      revConflict: false,
    })
  },

  setDraft: (draft) => {
    const { scriptId, savedSource, rev, drafts } = get()
    const nextDrafts =
      scriptId === null
        ? drafts
        : { ...drafts, [scriptId]: { draft, base: savedSource, rev } }
    set({ draft, dirty: draft !== savedSource, drafts: nextDrafts })
  },

  markSaved: (script) => {
    const source = script.source ?? get().draft
    set({
      name: script.name,
      description: script.description ?? '',
      status: script.status,
      rev: script.rev,
      savedSource: source,
      dirty: source !== get().draft,
      revConflict: false,
      diagnostics: [],
      validateOk: null,
      validatedAt: null,
      drafts: { ...get().drafts, [script.id]: { draft: source, base: source, rev: script.rev } },
    })
  },

  dropDraft: (scriptId) => {
    const drafts = { ...get().drafts }
    delete drafts[scriptId]
    set({ drafts })
  },

  setDiagnostics: (result) =>
    set({
      diagnostics: Array.isArray(result?.diagnostics) ? result.diagnostics : [],
      validateOk: Boolean(result?.ok),
      validatedAt: Date.now(),
    }),

  clearDiagnostics: () => set({ diagnostics: [], validateOk: null, validatedAt: null }),

  setRevConflict: (value) => set({ revConflict: value }),

  clear: () => {
    const { scriptId, drafts } = get()
    const nextDrafts = { ...drafts }
    if (scriptId) delete nextDrafts[scriptId]
    set({
      scriptId: null,
      name: '',
      description: '',
      status: 'ENABLED',
      rev: 0,
      draft: '',
      savedSource: '',
      dirty: false,
      diagnostics: [],
      validateOk: null,
      validatedAt: null,
      revConflict: false,
      drafts: nextDrafts,
    })
  },
}))
