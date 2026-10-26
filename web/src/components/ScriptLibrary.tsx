import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import type { Script, ScriptStatus } from '../api/types'
import { formatListTime, safeFileName } from '../lib/format'
import { downloadText, pickLuaFile } from '../lib/files'
import { useErrorNotice } from '../hooks/useNotice'
import { useEditorStore } from '../store/editor'
import { useUiStore } from '../store/ui'

const NEW_SCRIPT_SOURCE = '-- 在这里写脚本，Ctrl+S 保存\n'

export function ScriptLibrary() {
  const queryClient = useQueryClient()
  const notify = useUiStore((state) => state.notify)
  const selectedId = useUiStore((state) => state.selectedScriptId)
  const selectScript = useUiStore((state) => state.selectScript)
  const clearEditor = useEditorStore((state) => state.clear)
  const currentScriptId = useEditorStore((state) => state.scriptId)
  const reportError = useErrorNotice()

  const [search, setSearch] = useState('')
  const [confirmingId, setConfirmingId] = useState<string | null>(null)
  const [newName, setNewName] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  const scriptsQuery = useQuery({
    queryKey: ['scripts'],
    queryFn: api.scripts.list,
  })

  const items = scriptsQuery.data?.items ?? []

  const filtered = useMemo(() => {
    const keyword = search.trim().toLowerCase()
    if (!keyword) return items
    return items.filter((script) => {
      const haystack = `${script.name} ${script.description ?? ''}`.toLowerCase()
      return haystack.includes(keyword)
    })
  }, [items, search])

  const invalidateScripts = () => queryClient.invalidateQueries({ queryKey: ['scripts'] })

  const createMutation = useMutation({
    mutationFn: (name: string) => api.scripts.create({ name, source: NEW_SCRIPT_SOURCE }),
    onSuccess: (script) => {
      void invalidateScripts()
      selectScript(script.id)
      setNewName(null)
      notify({ tone: 'info', title: `已新建脚本 ${script.name}`, hint: '在中间编辑器里写内容，Ctrl+S 保存。' })
    },
    onError: (error) => reportError('新建脚本失败', error),
  })

  const importMutation = useMutation({
    mutationFn: (payload: { filename: string; content: string }) => api.scripts.importLua(payload),
    onSuccess: (script) => {
      void invalidateScripts()
      selectScript(script.id)
      notify({ tone: 'info', title: `已导入 ${script.name}`, hint: '确认内容后再运行；导入不会自动启用。' })
    },
    onError: (error) => reportError('导入 .lua 失败', error),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: string) => api.scripts.remove(id),
    onMutate: (id) => setBusyId(id),
    onSuccess: (_data, id) => {
      void invalidateScripts()
      if (selectedId === id) selectScript(null)
      if (currentScriptId === id) clearEditor()
      setConfirmingId(null)
      notify({ tone: 'info', title: '脚本已删除', hint: '热键绑定随脚本一起失效，如需保留请重新绑定。' })
    },
    onError: (error) => reportError('删除脚本失败', error),
    onSettled: () => setBusyId(null),
  })

  const toggleStatusMutation = useMutation({
    mutationFn: (script: Script) => {
      const nextStatus: ScriptStatus = script.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
      return api.scripts.setStatus(script.id, nextStatus)
    },
    onMutate: (script) => setBusyId(script.id),
    onSuccess: (updated, script) => {
      void invalidateScripts()
      void queryClient.invalidateQueries({ queryKey: ['script', script.id] })
      const wanted: ScriptStatus = script.status === 'ENABLED' ? 'DISABLED' : 'ENABLED'
      if (updated.status !== wanted) {
        notify({
          tone: 'warn',
          title: '后端没有接受启用状态修改',
          detail: `请求把脚本改成 ${wanted}，返回的仍是 ${updated.status}。`,
          hint: '确认后端 PUT /api/scripts/{id}/status 正常；暂时可以用热键的启用开关代替。',
        })
        return
      }
      notify({
        tone: 'info',
        title: updated.status === 'ENABLED' ? '脚本已启用' : '脚本已禁用',
        hint: updated.status === 'ENABLED' ? '绑定的热键现在可以触发它。' : '热键仍保留，但不会触发这个脚本。',
      })
    },
    onError: (error) => reportError('切换启用状态失败', error),
    onSettled: () => setBusyId(null),
  })

  const handleImport = async () => {
    const picked = await pickLuaFile()
    if (!picked) return
    if (!picked.content.trim()) {
      notify({
        tone: 'warn',
        title: '文件是空的',
        detail: `${picked.filename} 里没有可导入的内容。`,
        hint: '换一个 .lua 文件，或先在里面写点内容。',
      })
      return
    }
    importMutation.mutate(picked)
  }

  const handleExport = async (script: Script) => {
    setBusyId(script.id)
    try {
      const text = await api.scripts.exportText(script.id)
      downloadText(safeFileName(script.name, '.lua'), text)
      notify({ tone: 'info', title: `已导出 ${script.name}.lua`, hint: '文件在浏览器的下载目录里。' })
    } catch (error) {
      reportError('导出脚本失败', error)
    } finally {
      setBusyId(null)
    }
  }

  const submitNewName = () => {
    const name = (newName ?? '').trim()
    if (!name) {
      notify({ tone: 'warn', title: '脚本名不能为空', hint: '填一个能看出用途的名字，例如 连点器。' })
      return
    }
    if (items.some((script) => script.name === name)) {
      notify({ tone: 'warn', title: '已经有同名脚本', detail: name, hint: '换一个名字，或先删掉旧的。' })
      return
    }
    createMutation.mutate(name)
  }

  return (
    <section className="library">
      <div className="panel-header">
        <span className="panel-title">脚本库</span>
        <span className="panel-title text-3" style={{ fontWeight: 400 }}>
          {items.length}
        </span>
        <span className="spacer" />
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          title="从 .lua 文件导入"
          disabled={importMutation.isPending}
          onClick={() => void handleImport()}
        >
          导入
        </button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setNewName('')}>
          新建
        </button>
      </div>

      <div className="library-search">
        <input
          className="input"
          type="search"
          value={search}
          placeholder="搜索脚本名或描述"
          onChange={(event) => setSearch(event.target.value)}
        />
      </div>

      {newName !== null ? (
        <div className="library-search" style={{ display: 'flex', gap: 4 }}>
          <input
            className="input"
            autoFocus
            value={newName}
            placeholder="脚本名，例如 连点器"
            onChange={(event) => setNewName(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') submitNewName()
              if (event.key === 'Escape') setNewName(null)
            }}
          />
          <button type="button" className="btn btn-primary" disabled={createMutation.isPending} onClick={submitNewName}>
            建立
          </button>
        </div>
      ) : null}

      <div className="library-list">
        {scriptsQuery.isPending ? <div className="console-empty">正在读取脚本列表…</div> : null}

        {scriptsQuery.isError ? (
          <div className="banner banner-danger" style={{ borderBottom: 'none' }}>
            <div className="banner-body">
              <div className="banner-title">脚本列表读取失败</div>
              <div>后端没有返回 /api/scripts 的结果。</div>
              <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 4 }} onClick={() => void scriptsQuery.refetch()}>
                重试
              </button>
            </div>
          </div>
        ) : null}

        {!scriptsQuery.isPending && !scriptsQuery.isError && filtered.length === 0 ? (
          <div className="empty">
            <div className="empty-title">{items.length === 0 ? '还没有脚本' : '没有匹配的脚本'}</div>
            <div className="empty-desc">
              {items.length === 0
                ? '新建一个空脚本，或从本地的 .lua 文件导入。'
                : `没有名字或描述里含「${search.trim()}」的脚本。`}
            </div>
            {items.length === 0 ? (
              <button type="button" className="btn btn-secondary empty-action" onClick={() => setNewName('')}>
                新建脚本
              </button>
            ) : (
              <button type="button" className="btn btn-secondary empty-action" onClick={() => setSearch('')}>
                清空搜索
              </button>
            )}
          </div>
        ) : null}

        {filtered.map((script) => {
          const selected = script.id === selectedId
          const busy = busyId === script.id
          return (
            <div
              key={script.id}
              className={`library-item ${selected ? 'library-item-selected' : ''} ${
                script.status === 'DISABLED' ? 'library-item-disabled' : ''
              }`}
              role="button"
              tabIndex={0}
              onClick={() => selectScript(script.id)}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') {
                  event.preventDefault()
                  selectScript(script.id)
                }
              }}
            >
              <div className="library-item-main">
                <div className="library-item-name" title={script.description || script.name}>
                  {script.name}
                </div>
                <div className="library-item-meta">
                  <span className={`status-chip ${script.status === 'ENABLED' ? 'status-chip-on' : ''}`}>
                    {script.status === 'ENABLED' ? '已启用' : '已禁用'}
                  </span>
                  <span className="mono">r{script.rev}</span>
                </div>
              </div>

              <span className="library-item-time">{formatListTime(script.updatedAt)}</span>

              <div className="library-item-actions" onClick={(event) => event.stopPropagation()}>
                {confirmingId === script.id ? (
                  <div className="library-item-confirm">
                    <span>删除？</span>
                    <button
                      type="button"
                      className="btn btn-danger-ghost btn-sm"
                      disabled={busy}
                      onClick={() => deleteMutation.mutate(script.id)}
                    >
                      删除
                    </button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => setConfirmingId(null)}>
                      取消
                    </button>
                  </div>
                ) : (
                  <>
                    <button
                      type="button"
                      className="btn btn-ghost btn-sm"
                      disabled={busy}
                      title={script.status === 'ENABLED' ? '禁用这个脚本' : '启用这个脚本'}
                      onClick={() => toggleStatusMutation.mutate(script)}
                    >
                      {script.status === 'ENABLED' ? '禁用' : '启用'}
                    </button>
                    <button type="button" className="btn btn-ghost btn-sm" disabled={busy} title="导出为 .lua" onClick={() => void handleExport(script)}>
                      导出
                    </button>
                    <button type="button" className="btn btn-danger-ghost btn-sm" onClick={() => setConfirmingId(script.id)}>
                      删除
                    </button>
                  </>
                )}
              </div>
            </div>
          )
        })}
      </div>

      <div className="library-footer">
        <span>
          {filtered.length === items.length ? `共 ${items.length} 个脚本` : `筛出 ${filtered.length} / ${items.length} 个`}
        </span>
        <span className="spacer" />
        {scriptsQuery.isFetching ? <span>同步中</span> : null}
      </div>
    </section>
  )
}
