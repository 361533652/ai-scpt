/**
 * UI 状态（Zustand）。只放界面自己的状态：面板宽度、选中脚本、开关、通知条。
 * 服务端数据一律走 TanStack Query，不放这里。
 */
import { create } from 'zustand'
import { persist } from 'zustand/middleware'
import { bootstrapToken, clearToken, type TokenOrigin } from '../lib/token'
import type { LogLevel } from '../api/types'

export interface Notice {
  id: string
  tone: 'error' | 'warn' | 'info'
  title: string
  detail?: string
  /** 下一步动作。契约要求错误必须有原因和下一步。 */
  hint?: string
}

export type RightTab = 'chat' | 'kb'
export type LevelFilter = LogLevel | 'ALL'

export const PANEL_LIMITS = {
  left: { min: 200, max: 320 },
  right: { min: 320, max: 480 },
  console: { min: 120, max: 400, collapsed: 32 },
} as const

export function clamp(value: number, min: number, max: number): number {
  return Math.min(Math.max(value, min), max)
}

const boot = bootstrapToken()
let noticeSeq = 0

interface UiState {
  token: string
  tokenOrigin: TokenOrigin
  setToken: (token: string) => void
  dropToken: () => void

  leftWidth: number
  rightWidth: number
  consoleHeight: number
  consoleCollapsed: boolean
  rightTab: RightTab
  setLeftWidth: (width: number) => void
  setRightWidth: (width: number) => void
  setConsoleHeight: (height: number) => void
  toggleConsole: () => void
  setRightTab: (tab: RightTab) => void

  selectedScriptId: string | null
  selectScript: (id: string | null) => void

  settingsOpen: boolean
  luaHelpOpen: boolean
  hotkeyPanelOpen: boolean
  setSettingsOpen: (open: boolean) => void
  setLuaHelpOpen: (open: boolean) => void
  setHotkeyPanelOpen: (open: boolean) => void

  logLevel: LevelFilter
  setLogLevel: (level: LevelFilter) => void
  chatIncludeScript: boolean
  setChatIncludeScript: (include: boolean) => void

  notices: Notice[]
  notify: (notice: Omit<Notice, 'id'>) => string
  dismissNotice: (id: string) => void
}

export const useUiStore = create<UiState>()(
  persist(
    (set, get) => ({
      token: boot.token,
      tokenOrigin: boot.origin,
      setToken: (token) => set({ token, tokenOrigin: 'url' }),
      dropToken: () => {
        clearToken()
        set({ token: '', tokenOrigin: 'none', notices: [] })
      },

      leftWidth: 240,
      rightWidth: 360,
      consoleHeight: 200,
      consoleCollapsed: false,
      rightTab: 'chat',
      setLeftWidth: (width) =>
        set({ leftWidth: clamp(Math.round(width), PANEL_LIMITS.left.min, PANEL_LIMITS.left.max) }),
      setRightWidth: (width) =>
        set({ rightWidth: clamp(Math.round(width), PANEL_LIMITS.right.min, PANEL_LIMITS.right.max) }),
      setConsoleHeight: (height) =>
        set({
          consoleHeight: clamp(Math.round(height), PANEL_LIMITS.console.min, PANEL_LIMITS.console.max),
        }),
      toggleConsole: () => set({ consoleCollapsed: !get().consoleCollapsed }),
      setRightTab: (tab) => set({ rightTab: tab }),

      selectedScriptId: null,
      selectScript: (id) => set({ selectedScriptId: id }),

      settingsOpen: false,
      luaHelpOpen: false,
      hotkeyPanelOpen: false,
      setSettingsOpen: (open) => set({ settingsOpen: open }),
      setLuaHelpOpen: (open) => set({ luaHelpOpen: open }),
      setHotkeyPanelOpen: (open) => set({ hotkeyPanelOpen: open }),

      logLevel: 'ALL',
      setLogLevel: (level) => set({ logLevel: level }),
      chatIncludeScript: true,
      setChatIncludeScript: (include) => set({ chatIncludeScript: include }),

      notices: [],
      notify: (notice) => {
        noticeSeq += 1
        const id = `notice-${Date.now()}-${noticeSeq}`
        const next: Notice = { ...notice, id }
        set({ notices: [...get().notices, next].slice(-4) })
        return id
      },
      dismissNotice: (id) => set({ notices: get().notices.filter((notice) => notice.id !== id) }),
    }),
    {
      name: 'gmscpt.ui',
      partialize: (state) => ({
        leftWidth: state.leftWidth,
        rightWidth: state.rightWidth,
        consoleHeight: state.consoleHeight,
        consoleCollapsed: state.consoleCollapsed,
        rightTab: state.rightTab,
        logLevel: state.logLevel,
        chatIncludeScript: state.chatIncludeScript,
      }),
    },
  ),
)
