import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import { isApiError } from '../api/client'
import type { Hotkey, HotkeyAction, HotkeyEvent } from '../api/types'
import { describeAccelerator, isCapturableMouseButton, normalizeKeyEvent, normalizeMouseEvent } from '../lib/keys'
import { useErrorNotice } from '../hooks/useNotice'
import { useLuaApi } from '../hooks/useLuaApi'
import { useUiStore } from '../store/ui'

const EVENT_LABEL: Record<HotkeyEvent, string> = {
  KEY_PRESSED: '按下',
  KEY_RELEASED: '松开',
}

const ACTION_LABEL: Record<HotkeyAction, string> = {
  RUN: '运行',
  STOP: '停止',
  TOGGLE: '切换运行',
}

function newRow(): Hotkey {
  return { accelerator: '', event: 'KEY_PRESSED', action: 'RUN', enabled: true }
}

/** 409 ACCEL_CONFLICT 的 details 契约未写明，这里把可能出现的字段读成人话。 */
function describeConflict(details: Record<string, unknown> | null): string {
  if (!details) return ''
  const accelerator = typeof details.accelerator === 'string' ? details.accelerator : ''
  const owner =
    (typeof details.scriptName === 'string' && details.scriptName) ||
    (typeof details.scriptId === 'string' && details.scriptId) ||
    ''
  if (accelerator && owner) return `${describeAccelerator(accelerator)} 已被 ${owner} 占用。`
  if (accelerator) return `${describeAccelerator(accelerator)} 已被其他脚本占用。`
  if (owner) return `热键与 ${owner} 冲突。`
  return ''
}

export function HotkeyPanel({ scriptId }: { scriptId: string }) {
  const queryClient = useQueryClient()
  const notify = useUiStore((state) => state.notify)
  const reportError = useErrorNotice()
  const luaApi = useLuaApi()

  const [rows, setRows] = useState<Hotkey[]>([])
  const [capturing, setCapturing] = useState<number | null>(null)
  const [conflict, setConflict] = useState('')
  const capturingRef = useRef<number | null>(null)

  const hotkeysQuery = useQuery({
    queryKey: ['hotkeys', scriptId],
    queryFn: () => api.scripts.hotkeys(scriptId),
  })

  const settingsQuery = useQuery({
    queryKey: ['settings'],
    queryFn: api.settings.get,
    staleTime: 60_000,
  })

  useEffect(() => {
    if (hotkeysQuery.data) {
      setRows(Array.isArray(hotkeysQuery.data.items) ? hotkeysQuery.data.items : [])
      setConflict('')
    }
  }, [hotkeysQuery.data])

  useEffect(() => {
    setRows([])
    setCapturing(null)
    setConflict('')
  }, [scriptId])

  // 录制期间在 window 捕获阶段拦截按键与鼠标键，避免触发界面其它按钮。
  useEffect(() => {
    capturingRef.current = capturing
    if (capturing === null) return

    const stop = (event: Event) => {
      event.preventDefault()
      event.stopPropagation()
    }

    const onKeyDown = (event: KeyboardEvent) => {
      stop(event)
      if (event.key === 'Escape') {
        setCapturing(null)
        return
      }
      const accelerator = normalizeKeyEvent(event)
      if (!accelerator) {
        notify({
          tone: 'warn',
          title: '这个键暂时无法绑定',
          detail: `${event.key} 没有对应的键名。`,
          hint: '换成字母、数字、F1–F24、方向键或小键盘上的键。',
        })
        return
      }
      applyAccelerator(capturingRef.current, accelerator)
      setCapturing(null)
    }

    const onKeyUp = (event: KeyboardEvent) => stop(event)

    const onMouseDown = (event: MouseEvent) => {
      stop(event)
      if (event.button === 2) {
        // 右键保留为取消，避免把右键当作绑定又退不出录制。
        setCapturing(null)
        return
      }
      if (!isCapturableMouseButton(event.button)) {
        setCapturing(null)
        return
      }
      applyAccelerator(capturingRef.current, normalizeMouseEvent(event))
      setCapturing(null)
    }

    const onContextMenu = (event: MouseEvent) => stop(event)

    window.addEventListener('keydown', onKeyDown, true)
    window.addEventListener('keyup', onKeyUp, true)
    window.addEventListener('mousedown', onMouseDown, true)
    window.addEventListener('contextmenu', onContextMenu, true)
    return () => {
      window.removeEventListener('keydown', onKeyDown, true)
      window.removeEventListener('keyup', onKeyUp, true)
      window.removeEventListener('mousedown', onMouseDown, true)
      window.removeEventListener('contextmenu', onContextMenu, true)
    }
    // applyAccelerator 只依赖 notify，闭包内是稳定的索引与值更新。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [capturing, notify])

  const applyAccelerator = (index: number | null, accelerator: string) => {
    if (index === null) return
    const emergency = settingsQuery.data?.emergencyHotkey?.trim().toLowerCase()
    setRows((prev) =>
      prev.map((row, position) => (position === index ? { ...row, accelerator } : row)),
    )
    if (emergency && accelerator === emergency) {
      notify({
        tone: 'warn',
        title: '这个组合是急停热键',
        detail: `${describeAccelerator(accelerator)} 已用于急停，绑定到脚本上会互相抢占。`,
        hint: '换一个组合，或到设置里改急停热键。',
      })
    }
  }

  const saveMutation = useMutation({
    mutationFn: (items: Hotkey[]) =>
      api.scripts.putHotkeys(
        scriptId,
        items.map((row) => ({
          accelerator: row.accelerator,
          event: row.event,
          action: row.action,
          enabled: row.enabled,
        })),
      ),
    onSuccess: (response) => {
      setRows(Array.isArray(response.items) ? response.items : [])
      setConflict('')
      void queryClient.invalidateQueries({ queryKey: ['hotkeys', scriptId] })
      notify({ tone: 'info', title: '热键已保存', hint: '钩子已注册的情况下，立即生效。' })
    },
    onError: (error) => {
      if (isApiError(error) && error.code === 'ACCEL_CONFLICT') {
        const detail = describeConflict(error.details)
        setConflict(detail || '有组合与其他脚本冲突。')
        notify({
          tone: 'error',
          title: '热键冲突，没有保存',
          detail: detail || error.reason,
          hint: '把冲突的组合改成别的，或先到占用它的脚本里解绑。',
        })
        return
      }
      reportError('保存热键失败', error)
    },
  })

  const handleSave = () => {
    const filled = rows.filter((row) => row.accelerator.trim().length > 0)
    const duplicates = new Map<string, number>()
    for (const row of filled) {
      const key = row.accelerator.toLowerCase()
      duplicates.set(key, (duplicates.get(key) ?? 0) + 1)
    }
    const repeated = [...duplicates.entries()].filter(([, count]) => count > 1)
    if (repeated.length > 0) {
      setConflict(`${describeAccelerator(repeated[0][0])} 在这个脚本里出现了 ${repeated[0][1]} 次。`)
      notify({
        tone: 'warn',
        title: '同一个组合绑了多次',
        detail: `${describeAccelerator(repeated[0][0])} 重复了。`,
        hint: '每个组合只保留一条，或给它们设置不同的事件与动作。',
      })
      return
    }
    saveMutation.mutate(filled)
  }

  return (
    <div className="hotkey-panel">
      <div className="hotkey-head">
        <span className="panel-title">热键</span>
        <span className="hotkey-hint">点「录制」后按键；Esc 或右键取消</span>
        <span className="spacer" />
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setRows((prev) => [...prev, newRow()])}>
          添加
        </button>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          disabled={hotkeysQuery.isFetching}
          onClick={() => void hotkeysQuery.refetch()}
        >
          重载
        </button>
        <button type="button" className="btn btn-primary" disabled={saveMutation.isPending} onClick={handleSave}>
          {saveMutation.isPending ? '保存中' : '保存热键'}
        </button>
      </div>

      {conflict ? <div className="banner banner-warn" style={{ borderBottom: '1px solid var(--border-subtle)' }}>{conflict}</div> : null}

      {hotkeysQuery.isError ? (
        <div className="banner banner-danger">热键读取失败：{hotkeysQuery.error instanceof Error ? hotkeysQuery.error.message : '未知原因'}</div>
      ) : null}

      {rows.length === 0 ? (
        <div className="hotkey-foot">
          <span className="hotkey-hint">
            还没有绑定热键。添加一条，按键触发脚本；也可以只靠编辑器里的 F5 手动运行。
          </span>
        </div>
      ) : null}

      {rows.map((row, index) => (
        <div className="hotkey-row" key={`${index}-${row.accelerator}`}>
          <button
            type="button"
            className={`hotkey-capture ${capturing === index ? 'hotkey-capture-active' : ''} ${
              row.accelerator ? '' : 'hotkey-capture-empty'
            }`}
            onClick={() => setCapturing(capturing === index ? null : index)}
          >
            <span>{capturing === index ? '按下按键…' : row.accelerator ? describeAccelerator(row.accelerator) : '录制'}</span>
            <span className="mono text-3">{row.accelerator || '—'}</span>
          </button>

          <select
            className="select"
            value={row.event}
            onChange={(event) =>
              setRows((prev) =>
                prev.map((item, position) =>
                  position === index ? { ...item, event: event.target.value as HotkeyEvent } : item,
                ),
              )
            }
          >
            {(Object.keys(EVENT_LABEL) as HotkeyEvent[]).map((value) => (
              <option key={value} value={value}>
                {EVENT_LABEL[value]}
              </option>
            ))}
          </select>

          <select
            className="select"
            value={row.action}
            onChange={(event) =>
              setRows((prev) =>
                prev.map((item, position) =>
                  position === index ? { ...item, action: event.target.value as HotkeyAction } : item,
                ),
              )
            }
          >
            {(Object.keys(ACTION_LABEL) as HotkeyAction[]).map((value) => (
              <option key={value} value={value}>
                {ACTION_LABEL[value]}
              </option>
            ))}
          </select>

          <label className="check">
            <input
              type="checkbox"
              checked={row.enabled}
              onChange={(event) =>
                setRows((prev) =>
                  prev.map((item, position) => (position === index ? { ...item, enabled: event.target.checked } : item)),
                )
              }
            />
            启用
          </label>

          <button
            type="button"
            className="btn btn-ghost btn-sm"
            title="删除这一条"
            onClick={() => setRows((prev) => prev.filter((_, position) => position !== index))}
          >
            ×
          </button>
        </div>
      ))}

      {luaApi.data?.events?.length ? (
        <div className="hotkey-foot">
          <span className="hotkey-hint">
            可用事件：{luaApi.data.events.map((item) => (typeof item === 'string' ? item : item.name)).filter(Boolean).join('、')}
          </span>
        </div>
      ) : null}
    </div>
  )
}
