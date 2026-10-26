import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import type { LogEntry, LogLevel } from '../api/types'
import { formatLogTime, formatLogTimeFull, levelLabel, safeFileName } from '../lib/format'
import { downloadText } from '../lib/files'
import { useLogStream } from '../hooks/useLogStream'
import { hookState, normalizeRunning, useHealth } from '../hooks/useHealth'
import { useErrorNotice } from '../hooks/useNotice'
import { useUiStore, type LevelFilter } from '../store/ui'

const LEVELS: LogLevel[] = ['DEBUG', 'INFO', 'WARN', 'ERROR']
const LEVEL_WEIGHT: Record<LogLevel, number> = { DEBUG: 10, INFO: 20, WARN: 30, ERROR: 40 }

function passesFilter(entry: LogEntry, filter: LevelFilter): boolean {
  if (filter === 'ALL') return true
  return LEVEL_WEIGHT[entry.level] >= LEVEL_WEIGHT[filter]
}

export function ConsolePanel() {
  const queryClient = useQueryClient()
  const level = useUiStore((state) => state.logLevel)
  const setLevel = useUiStore((state) => state.setLogLevel)
  const collapsed = useUiStore((state) => state.consoleCollapsed)
  const toggleConsole = useUiStore((state) => state.toggleConsole)
  const notify = useUiStore((state) => state.notify)
  const reportError = useErrorNotice()

  const health = useHealth()
  const stream = useLogStream(true)
  const [stick, setStick] = useState(true)
  const bodyRef = useRef<HTMLDivElement | null>(null)

  const running = normalizeRunning(health.data?.running)
  const hook = hookState(health.data)

  const visible = useMemo(() => stream.entries.filter((entry) => passesFilter(entry, level)), [stream.entries, level])

  useEffect(() => {
    const body = bodyRef.current
    if (!body || !stick || collapsed) return
    body.scrollTop = body.scrollHeight
  }, [visible, stick, collapsed])

  const stopAll = useMutation({
    mutationFn: () => api.runs.stopAll(),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['health'] })
      void queryClient.invalidateQueries({ queryKey: ['runs'] })
      notify({
        tone: 'info',
        title: result.stopped?.length ? `已停止 ${result.stopped.length} 个脚本` : '没有正在运行的脚本',
        detail: result.stopped?.join('、'),
        hint: '脚本会在当前指令结束后退出；卡死的循环由超时判 killed。',
      })
    },
    onError: (error) => reportError('停止全部失败', error),
  })

  const handleExport = () => {
    if (visible.length === 0) {
      notify({ tone: 'warn', title: '没有可导出的日志', hint: '先把过滤放宽到「全部」，或等脚本产生输出。' })
      return
    }
    const text = visible
      .map((entry) => `${formatLogTimeFull(entry.timeMillis)} ${entry.level.padEnd(5)} ${entry.source} ${entry.message}`)
      .join('\n')
    downloadText(safeFileName(`gmscpt-logs-${new Date().toISOString().slice(0, 16).replace(':', '')}`, '.log'), `${text}\n`)
    notify({ tone: 'info', title: `已导出 ${visible.length} 条日志`, hint: '文件在浏览器的下载目录里。' })
  }

  const streamLabel =
    stream.status === 'open'
      ? '实时'
      : stream.status === 'connecting'
        ? '连接中'
        : stream.status === 'retry'
          ? '重连中'
          : '已停止'

  return (
    <>
      <div className="console-header">
        <div className="console-title">
          <span className={`dot ${running.length > 0 ? 'dot-ok' : 'dot-idle'}`} />
          运行控制台
        </div>
        <span className="console-count">
          {visible.length}/{stream.entries.length} 条 · {streamLabel}
        </span>

        <div className="console-filters">
          <button
            type="button"
            className={`console-filter ${level === 'ALL' ? 'console-filter-active' : ''}`}
            onClick={() => setLevel('ALL')}
          >
            全部
          </button>
          {LEVELS.map((item) => (
            <button
              key={item}
              type="button"
              className={`console-filter ${level === item ? 'console-filter-active' : ''}`}
              onClick={() => setLevel(item)}
            >
              {levelLabel(item)}
            </button>
          ))}
        </div>

        <span className="spacer" />

        {running.length > 0 ? (
          <button type="button" className="btn btn-secondary btn-sm" disabled={stopAll.isPending} onClick={() => stopAll.mutate()}>
            停止全部
          </button>
        ) : null}
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => stream.setPaused(!stream.paused)}>
          {stream.paused ? `继续${stream.pendingCount > 0 ? `（${stream.pendingCount}）` : ''}` : '暂停'}
        </button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={stream.clear}>
          清空
        </button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={handleExport}>
          导出 .log
        </button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={toggleConsole} title={collapsed ? '展开控制台' : '收起控制台'}>
          {collapsed ? '展开' : '收起'}
        </button>
      </div>

      {collapsed ? null : (
        <>
          {hook.tone !== 'ok' ? (
            <div className="console-hint">
              钩子未启用：{hook.detail}
              <span className="spacer" />
              <button
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => useUiStore.getState().setSettingsOpen(true)}
              >
                打开设置
              </button>
            </div>
          ) : null}

          {stream.status === 'retry' ? <div className="console-hint">{stream.detail}</div> : null}

          {stream.paused ? (
            <div className="console-paused">
              已暂停显示，新日志仍在接收{stream.pendingCount > 0 ? `（积压 ${stream.pendingCount} 条）` : ''}，点「继续」一次性补上。
            </div>
          ) : null}

          <div
            className="console-body"
            ref={bodyRef}
            onScroll={(event) => {
              const target = event.currentTarget
              setStick(target.scrollHeight - target.scrollTop - target.clientHeight < 24)
            }}
          >
            {visible.length === 0 ? (
              <div className="console-empty">
                {stream.entries.length === 0
                  ? '还没有日志。运行脚本后，标准输出和引擎事件会实时出现在这里。'
                  : `当前过滤条件下没有日志（共 ${stream.entries.length} 条，切到「全部」可以看全）。`}
              </div>
            ) : (
              visible.map((entry) => (
                <div key={entry.seq} className={`log-row ${entry.level === 'ERROR' ? 'log-row-error' : ''}`}>
                  <span className="log-time" title={formatLogTimeFull(entry.timeMillis)}>
                    {formatLogTime(entry.timeMillis)}
                  </span>
                  <span className={`log-level log-level-${entry.level.toLowerCase()}`}>{levelLabel(entry.level)}</span>
                  {entry.source ? <span className="log-source">{entry.source}</span> : null}
                  <span className="log-message">{entry.message}</span>
                </div>
              ))
            )}
          </div>
        </>
      )}
    </>
  )
}
