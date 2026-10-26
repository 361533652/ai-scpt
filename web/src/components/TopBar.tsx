import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import { formatDuration, formatShortDuration, parseTime } from '../lib/format'
import { hookState, normalizeRunning, useHealth } from '../hooks/useHealth'
import { useEditorStore } from '../store/editor'
import { useUiStore } from '../store/ui'
import type { RunExit } from '../api/types'

const EXIT_LABEL: Record<RunExit, string> = {
  OK: '正常结束',
  LUA_ERROR: '脚本报错',
  STOPPED: '手动停止',
  TIMEOUT: '超时',
  KILLED: '被强杀',
}

/** 每秒走动的计时，只在运行中启用。 */
function useTicker(active: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [active])
  return now
}

export function TopBar() {
  const health = useHealth()
  const dirty = useEditorStore((state) => state.dirty)
  const scriptName = useEditorStore((state) => state.name)
  const scriptId = useEditorStore((state) => state.scriptId)
  const setSettingsOpen = useUiStore((state) => state.setSettingsOpen)
  const notify = useUiStore((state) => state.notify)

  const running = normalizeRunning(health.data?.running)
  const currentRun = scriptId ? running.find((item) => item.scriptId === scriptId) : undefined
  const startedAt = parseTime(currentRun?.startedAt ?? null)
  const now = useTicker(Boolean(currentRun))

  const lastRunQuery = useQuery({
    queryKey: ['runs', scriptId, 1],
    queryFn: () => api.runs.list(scriptId ?? undefined, 1),
    enabled: Boolean(scriptId) && !currentRun,
    staleTime: 5_000,
  })
  const lastRun = lastRunQuery.data?.items?.[0]

  const hook = hookState(health.data)
  const ollama = health.data?.ollama
  const ollamaReachable = Boolean(ollama?.reachable)

  const handleHookClick = () => {
    if (hook.tone === 'ok') return
    notify({
      tone: 'warn',
      title: hook.text,
      detail: hook.detail,
      hint: '在设置里确认急停热键与运行参数；钩子注册失败通常是权限不足，用管理员权限重启后端。',
    })
    setSettingsOpen(true)
  }

  return (
    <header className="topbar">
      <div className="topbar-left">
        <div className="topbar-script">
          <span className="topbar-script-name" title={scriptName || undefined}>
            {scriptName || '未选择脚本'}
          </span>
          <span className={`dirty-dot ${dirty ? 'dirty-dot-on' : ''}`} title={dirty ? '有未保存的修改' : '已保存'} />
          {dirty ? <span className="text-3" style={{ fontSize: 12 }}>未保存</span> : null}
        </div>
        {running.length > 1 ? (
          <span className="pill" title="其他脚本也在运行，点开控制台可以一次停掉">
            另有 {running.length - 1} 个脚本运行中
          </span>
        ) : null}
      </div>

      <div className="topbar-center">
        {currentRun ? (
          <span className="pill pill-ok">
            <span className="dot dot-ok" />
            运行中 {formatDuration(startedAt ? now - startedAt : 0)}
          </span>
        ) : (
          <span className="pill">
            <span className="dot dot-idle" />
            已停止
            {lastRun ? (
              <>
                {' · '}
                {lastRun.exit ? EXIT_LABEL[lastRun.exit] ?? lastRun.exit : '已结束'}
                {(() => {
                  const start = parseTime(lastRun.startedAt)
                  const end = parseTime(lastRun.endedAt)
                  return start && end ? ` ${formatShortDuration(end - start)}` : ''
                })()}
              </>
            ) : null}
          </span>
        )}
        <button type="button" className={`pill ${hook.tone === 'warn' ? 'pill-warn' : hook.tone === 'error' ? 'pill-danger' : ''} pill-button`} title={hook.detail} onClick={handleHookClick}>
          <span className={`dot ${hook.tone === 'ok' ? 'dot-ok' : hook.tone === 'warn' ? 'dot-warn' : hook.tone === 'error' ? 'dot-danger' : 'dot-idle'}`} />
          {hook.text}
        </button>
      </div>

      <div className="topbar-right">
        {health.isError ? (
          <button
            type="button"
            className="pill pill-danger pill-button"
            title="每秒自动重试一次，也可以点这里立即重试"
            onClick={() => void health.refetch()}
          >
            <span className="dot dot-danger" />
            服务离线
          </button>
        ) : null}
        <button type="button" className="pill pill-button" onClick={() => setSettingsOpen(true)}>
          <span className={`dot ${ollamaReachable ? 'dot-ok' : 'dot-warn'}`} />
          Ollama {ollamaReachable ? '可达' : '不可达'}
        </button>
        <button type="button" className="btn btn-secondary" onClick={() => setSettingsOpen(true)}>
          设置
        </button>
      </div>
    </header>
  )
}
