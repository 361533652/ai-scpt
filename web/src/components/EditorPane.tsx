import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import { isApiError } from '../api/client'
import type { Script } from '../api/types'
import { CodeEditor } from './CodeEditor'
import { HotkeyPanel } from './HotkeyPanel'
import { buildCompletionItems, useLuaApi } from '../hooks/useLuaApi'
import { normalizeRunning, useHealth } from '../hooks/useHealth'
import { useErrorNotice } from '../hooks/useNotice'
import { useEditorStore } from '../store/editor'
import { useUiStore } from '../store/ui'
import { getEditorBridge } from '../lib/editorBridge'
import { formatClock } from '../lib/format'

export function EditorPane() {
  const queryClient = useQueryClient()
  const selectedScriptId = useUiStore((state) => state.selectedScriptId)
  const selectScript = useUiStore((state) => state.selectScript)
  const notify = useUiStore((state) => state.notify)
  const hotkeyPanelOpen = useUiStore((state) => state.hotkeyPanelOpen)
  const setHotkeyPanelOpen = useUiStore((state) => state.setHotkeyPanelOpen)
  const setLuaHelpOpen = useUiStore((state) => state.setLuaHelpOpen)
  const reportError = useErrorNotice()
  const health = useHealth()
  const luaApi = useLuaApi()

  const scriptId = useEditorStore((state) => state.scriptId)
  const name = useEditorStore((state) => state.name)
  const description = useEditorStore((state) => state.description)
  const rev = useEditorStore((state) => state.rev)
  const draft = useEditorStore((state) => state.draft)
  const dirty = useEditorStore((state) => state.dirty)
  const diagnostics = useEditorStore((state) => state.diagnostics)
  const validateOk = useEditorStore((state) => state.validateOk)
  const validatedAt = useEditorStore((state) => state.validatedAt)
  const revConflict = useEditorStore((state) => state.revConflict)
  const loadScript = useEditorStore((state) => state.loadScript)
  const setDraft = useEditorStore((state) => state.setDraft)
  const markSaved = useEditorStore((state) => state.markSaved)
  const dropDraft = useEditorStore((state) => state.dropDraft)
  const setDiagnostics = useEditorStore((state) => state.setDiagnostics)
  const clearDiagnostics = useEditorStore((state) => state.clearDiagnostics)
  const setRevConflict = useEditorStore((state) => state.setRevConflict)

  const [cursor, setCursor] = useState({ line: 1, column: 1 })
  const [saving, setSaving] = useState(false)
  const loadedKeyRef = useRef('')

  const scriptQuery = useQuery({
    queryKey: ['script', selectedScriptId],
    queryFn: () => api.scripts.get(selectedScriptId as string),
    enabled: Boolean(selectedScriptId),
  })

  // 服务端版本变化（id + rev）时载入编辑器；同 rev 的重复请求不覆盖草稿。
  useEffect(() => {
    const data = scriptQuery.data
    if (!data) return
    const key = `${data.id}:${data.rev}`
    if (loadedKeyRef.current === key) return
    loadedKeyRef.current = key
    loadScript(data)
  }, [scriptQuery.data, loadScript])

  useEffect(() => {
    if (!scriptQuery.isError) return
    reportError('读取脚本失败', scriptQuery.error)
  }, [scriptQuery.isError, scriptQuery.error, reportError])

  const completions = useMemo(() => buildCompletionItems(luaApi.data), [luaApi.data])

  const running = normalizeRunning(health.data?.running)
  const isRunning = Boolean(scriptId) && running.some((item) => item.scriptId === scriptId)

  const save = useCallback(async () => {
    const state = useEditorStore.getState()
    if (!state.scriptId) return
    setSaving(true)
    try {
      const updated = await api.scripts.update(state.scriptId, {
        name: state.name,
        description: state.description,
        source: state.draft,
        rev: state.rev,
      })
      markSaved(updated)
      queryClient.setQueryData(['script', updated.id], updated)
      void queryClient.invalidateQueries({ queryKey: ['scripts'] })
      notify({ tone: 'info', title: '已保存', detail: `rev ${updated.rev}`, hint: '热键触发的脚本会在下次运行时使用新内容。' })
    } catch (error) {
      if (isApiError(error) && error.code === 'REV_CONFLICT') {
        setRevConflict(true)
        notify({
          tone: 'error',
          title: '内容已被别处修改',
          detail: error.reason,
          hint: '载入最新版本，或用当前内容覆盖保存。',
        })
        return
      }
      reportError('保存失败', error)
    } finally {
      setSaving(false)
    }
  }, [markSaved, notify, queryClient, reportError, setRevConflict])

  const run = useCallback(async () => {
    const state = useEditorStore.getState()
    if (!state.scriptId) return
    if (state.dirty) {
      notify({
        tone: 'warn',
        title: '运行的是已保存的版本',
        detail: '编辑器里有未保存的修改，这次运行不会带上它们。',
        hint: '按 Ctrl+S 保存后再运行。',
      })
    }
    try {
      const result = await api.scripts.run(state.scriptId, { trigger: 'manual' })
      void queryClient.invalidateQueries({ queryKey: ['health'] })
      void queryClient.invalidateQueries({ queryKey: ['runs'] })
      notify({ tone: 'info', title: `已开始运行 ${state.name}`, detail: `runId ${result.runId}`, hint: '运行输出在底部控制台，随时可以停止。' })
    } catch (error) {
      if (isApiError(error)) {
        if (error.code === 'LUA_COMPILE_ERROR') {
          notify({
            tone: 'error',
            title: '脚本有语法错误，没有运行',
            detail: error.reason,
            hint: '点编辑器工具条上的「校验」，把问题改完再运行。',
          })
          return
        }
        if (error.code === 'SCRIPT_ALREADY_RUNNING') {
          notify({ tone: 'warn', title: '脚本已经在运行', detail: error.reason, hint: '先点「停止」，再重新运行。' })
          return
        }
      }
      reportError('运行失败', error)
    }
  }, [notify, queryClient, reportError])

  const stop = useCallback(async () => {
    const state = useEditorStore.getState()
    if (!state.scriptId) return
    try {
      const result = await api.scripts.stop(state.scriptId)
      void queryClient.invalidateQueries({ queryKey: ['health'] })
      void queryClient.invalidateQueries({ queryKey: ['runs'] })
      if (result.stopped) {
        notify({ tone: 'info', title: '已发出停止指令', hint: '脚本会在当前指令结束后退出，卡住的循环按阶梯强杀。' })
      } else {
        notify({ tone: 'warn', title: '后端报告没有正在运行的实例', detail: state.name, hint: '状态栏会在下次刷新时更新。' })
      }
    } catch (error) {
      reportError('停止失败', error)
    }
  }, [notify, queryClient, reportError])

  const validate = useCallback(async () => {
    const state = useEditorStore.getState()
    if (!state.scriptId) return
    try {
      const result = await api.scripts.validate(state.scriptId, state.draft)
      setDiagnostics(result)
      const count = result.diagnostics?.length ?? 0
      if (result.ok) {
        notify({ tone: 'info', title: '校验通过', hint: '语法没问题，可以运行。' })
      } else {
        notify({
          tone: 'warn',
          title: `校验发现 ${count} 处问题`,
          detail: result.diagnostics?.[0]?.message,
          hint: '点编辑器上方的诊断条可以跳到对应行。',
        })
      }
    } catch (error) {
      if (isApiError(error) && error.code === 'LUA_COMPILE_ERROR') {
        notify({
          tone: 'error',
          title: '脚本无法编译',
          detail: error.reason,
          hint: '按提示的行号修改；编辑器里改完可以再校验一次。',
        })
        return
      }
      reportError('校验失败', error)
    }
  }, [notify, reportError, setDiagnostics])

  const reloadLatest = useCallback(async () => {
    const state = useEditorStore.getState()
    if (!state.scriptId) return
    try {
      const latest = await api.scripts.get(state.scriptId)
      dropDraft(latest.id)
      loadedKeyRef.current = `${latest.id}:${latest.rev}`
      loadScript(latest)
      queryClient.setQueryData(['script', latest.id], latest)
      setRevConflict(false)
      notify({ tone: 'info', title: '已载入最新版本', detail: `rev ${latest.rev}`, hint: '你刚才没保存的修改已经丢弃。' })
    } catch (error) {
      reportError('载入最新版本失败', error)
    }
  }, [dropDraft, loadScript, notify, queryClient, reportError, setRevConflict])

  const forceSave = useCallback(async () => {
    const state = useEditorStore.getState()
    if (!state.scriptId) return
    setSaving(true)
    try {
      const latest = await api.scripts.get(state.scriptId)
      const updated = await api.scripts.update(state.scriptId, {
        name: state.name,
        description: state.description,
        source: state.draft,
        rev: latest.rev,
      })
      markSaved(updated)
      queryClient.setQueryData(['script', updated.id], updated)
      void queryClient.invalidateQueries({ queryKey: ['scripts'] })
      setRevConflict(false)
      notify({ tone: 'info', title: '已用当前内容覆盖保存', detail: `rev ${updated.rev}`, hint: '别处的修改被这次保存覆盖了。' })
    } catch (error) {
      reportError('覆盖保存失败', error)
    } finally {
      setSaving(false)
    }
  }, [markSaved, notify, queryClient, reportError, setRevConflict])

  const createFirstScript = useMutation({
    mutationFn: () =>
      api.scripts.create({ name: `新脚本 ${Date.now().toString().slice(-4)}`, source: '-- 在这里写脚本，Ctrl+S 保存\n' }),
    onSuccess: (script: Script) => {
      void queryClient.invalidateQueries({ queryKey: ['scripts'] })
      selectScript(script.id)
    },
    onError: (error) => reportError('新建脚本失败', error),
  })

  if (!selectedScriptId) {
    return (
      <section className="editor-pane">
        <div className="editor-tabbar">
          <span className="editor-tab text-3">没有打开的脚本</span>
        </div>
        <div className="empty">
          <div className="empty-title">未选择脚本</div>
          <div className="empty-desc">在左侧脚本库里点一个脚本开始编辑，或者直接新建一个。</div>
          <button
            type="button"
            className="btn btn-secondary empty-action"
            disabled={createFirstScript.isPending}
            onClick={() => createFirstScript.mutate()}
          >
            新建脚本
          </button>
        </div>
      </section>
    )
  }

  return (
    <section className="editor-pane">
      <div className="editor-tabbar">
        <span className="editor-tab" title={description || name}>
          <span className={`dirty-dot ${dirty ? 'dirty-dot-on' : ''}`} />
          <span className="editor-tab-name">{name || '未命名'}</span>
          <span className="editor-tab-meta">rev {rev}</span>
        </span>
        <span className="spacer" />
        <div className="editor-toolbar">
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setLuaHelpOpen(true)}>
            Lua 帮助
          </button>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            aria-pressed={hotkeyPanelOpen}
            onClick={() => setHotkeyPanelOpen(!hotkeyPanelOpen)}
          >
            热键
          </button>
          <button type="button" className="btn btn-secondary" disabled={!scriptId} onClick={() => void validate()}>
            校验
          </button>
          <button type="button" className="btn btn-secondary" disabled={saving || !dirty} onClick={() => void save()}>
            {saving ? '保存中' : '保存'}
          </button>
          {isRunning ? (
            <button type="button" className="btn btn-danger" onClick={() => void stop()}>
              停止
            </button>
          ) : (
            <button type="button" className="btn btn-primary" onClick={() => void run()}>
              运行 F5
            </button>
          )}
        </div>
      </div>

      {revConflict ? (
        <div className="banner banner-danger">
          <div className="banner-body">
            <div className="banner-title">内容已被别处修改</div>
            <div>保存被拒绝：服务端 rev 已经变了，直接保存会覆盖别人的修改。</div>
          </div>
          <div className="banner-actions">
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => void reloadLatest()}>
              载入最新版本
            </button>
            <button type="button" className="btn btn-danger-ghost btn-sm" disabled={saving} onClick={() => void forceSave()}>
              用我的内容覆盖
            </button>
          </div>
        </div>
      ) : null}

      {scriptQuery.isError ? (
        <div className="banner banner-danger">
          <div className="banner-body">脚本读取失败：{scriptQuery.error instanceof Error ? scriptQuery.error.message : '未知原因'}</div>
          <div className="banner-actions">
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => void scriptQuery.refetch()}>
              重试
            </button>
          </div>
        </div>
      ) : null}

      {diagnostics.length > 0 ? (
        <div className="diagnostics-bar">
          <span className="diagnostic-line">{diagnostics.length} 处问题</span>
          {diagnostics.slice(0, 12).map((item, index) => (
            <button
              key={`${item.line}-${item.column}-${index}`}
              type="button"
              className="diagnostic-chip"
              onClick={() => getEditorBridge()?.jumpToLine(item.line, item.column)}
            >
              <span className="mono">
                {item.line}:{item.column}
              </span>
              <span>{item.message}</span>
            </button>
          ))}
          {diagnostics.length > 12 ? <span className="text-3">还有 {diagnostics.length - 12} 处，见控制台</span> : null}
          <span className="spacer" />
          <button type="button" className="btn btn-ghost btn-sm" onClick={clearDiagnostics}>
            清除
          </button>
        </div>
      ) : null}

      {validateOk === true && diagnostics.length === 0 ? (
        <div className="diagnostics-bar">
          <span style={{ color: 'var(--success)' }}>校验通过</span>
          <span className="text-3">{validatedAt ? `于 ${formatClock(new Date(validatedAt))}` : ''}</span>
          <span className="spacer" />
          <button type="button" className="btn btn-ghost btn-sm" onClick={clearDiagnostics}>
            清除
          </button>
        </div>
      ) : null}

      {hotkeyPanelOpen && scriptId ? <HotkeyPanel scriptId={scriptId} /> : null}

      <CodeEditor
        value={draft}
        diagnostics={diagnostics}
        completions={completions}
        onChange={setDraft}
        onSave={() => void save()}
        onRun={() => void run()}
        onCursorChange={(line, column) => setCursor({ line, column })}
      />

      <div className="editor-status">
        <span>
          行 {cursor.line} 列 {cursor.column}
        </span>
        <span>{draft.length} 字符</span>
        <span>rev {rev}</span>
        <span>{dirty ? '有未保存的修改' : '已保存'}</span>
        <span className="spacer" />
        {luaApi.isError ? <span className="warn-text">Lua 补全文档未加载</span> : null}
        <span>Ctrl+S 保存 · F5 运行</span>
      </div>
    </section>
  )
}
