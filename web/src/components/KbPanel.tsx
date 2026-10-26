import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import type { KbSearchMode, KnowledgeEntry } from '../api/types'
import { formatListTime, truncate } from '../lib/format'
import { useErrorNotice } from '../hooks/useNotice'
import { useUiStore } from '../store/ui'

interface DraftEntry {
  id: string | null
  game: string
  title: string
  description: string
  tags: string
  script: string
}

const EMPTY_DRAFT: DraftEntry = { id: null, game: '', title: '', description: '', tags: '', script: '' }

function toDraft(entry: KnowledgeEntry): DraftEntry {
  return {
    id: entry.id,
    game: entry.game ?? '',
    title: entry.title ?? '',
    description: entry.description ?? '',
    tags: (entry.tags ?? []).join(', '),
    script: entry.script ?? '',
  }
}

/** 正文摘要：描述优先，没有描述就截代码。 */
function summaryOf(entry: KnowledgeEntry, max = 90): string {
  const description = entry.description?.trim()
  if (description) return truncate(description, max)
  return truncate(entry.script ?? '', max)
}

function KbEditorDialog({
  draft,
  saving,
  onChange,
  onCancel,
  onSubmit,
}: {
  draft: DraftEntry
  saving: boolean
  onChange: (draft: DraftEntry) => void
  onCancel: () => void
  onSubmit: () => void
}) {
  return (
    <div
      className="modal-backdrop"
      onKeyDown={(event) => {
        if (event.key === 'Escape') onCancel()
      }}
    >
      <div className="modal" style={{ maxWidth: 600 }}>
        <div className="modal-header">
          <span className="modal-title">{draft.id ? '编辑知识库条目' : '新建知识库条目'}</span>
          <button type="button" className="btn btn-ghost" onClick={onCancel}>
            关闭
          </button>
        </div>
        <div className="modal-body">
          <div className="grid-2">
            <label className="field">
              <span className="field-label">标题</span>
              <input
                className="input"
                autoFocus
                value={draft.title}
                placeholder="例如：坐标移动的写法"
                onChange={(event) => onChange({ ...draft, title: event.target.value })}
              />
            </label>
            <label className="field">
              <span className="field-label">适用游戏（可留空）</span>
              <input
                className="input"
                value={draft.game}
                placeholder="留空表示通用"
                onChange={(event) => onChange({ ...draft, game: event.target.value })}
              />
            </label>
          </div>
          <label className="field">
            <span className="field-label">一句话说明（可留空）</span>
            <input
              className="input"
              value={draft.description}
              placeholder="这段代码解决什么问题"
              onChange={(event) => onChange({ ...draft, description: event.target.value })}
            />
          </label>
          <label className="field">
            <span className="field-label">标签（逗号分隔，可留空）</span>
            <input
              className="input"
              value={draft.tags}
              placeholder="鼠标, 示例"
              onChange={(event) => onChange({ ...draft, tags: event.target.value })}
            />
          </label>
          <label className="field">
            <span className="field-label">Lua 正文</span>
            <textarea
              className="textarea kb-editor-content"
              value={draft.script}
              placeholder="贴一段能直接复制到脚本里的代码"
              onChange={(event) => onChange({ ...draft, script: event.target.value })}
            />
          </label>
        </div>
        <div className="modal-footer">
          <span className="text-3" style={{ fontSize: 12 }}>
            一期搜索按标题、说明、标签与正文的子串匹配，把关键词写进标题里更容易被搜到。
          </span>
          <span className="spacer" />
          <button type="button" className="btn btn-secondary" onClick={onCancel}>
            取消
          </button>
          <button type="button" className="btn btn-primary" disabled={saving} onClick={onSubmit}>
            {saving ? '保存中' : '保存'}
          </button>
        </div>
      </div>
    </div>
  )
}

export function KbPanel() {
  const queryClient = useQueryClient()
  const notify = useUiStore((state) => state.notify)
  const selectScript = useUiStore((state) => state.selectScript)
  const reportError = useErrorNotice()

  const [keyword, setKeyword] = useState('')
  const [submittedQuery, setSubmittedQuery] = useState('')
  const [mode, setMode] = useState<KbSearchMode>('keyword')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [draft, setDraft] = useState<DraftEntry | null>(null)
  const [confirmingId, setConfirmingId] = useState<string | null>(null)

  const listQuery = useQuery({
    queryKey: ['kb'],
    queryFn: () => api.kb.list(),
    staleTime: 10_000,
  })

  const searchQuery = useQuery({
    queryKey: ['kb-search', submittedQuery, mode],
    queryFn: () => api.kb.search({ query: submittedQuery, topK: 20, mode }),
    enabled: submittedQuery.length > 0,
    staleTime: 10_000,
  })

  const hits = submittedQuery ? searchQuery.data?.hits ?? [] : []
  const entries: KnowledgeEntry[] = submittedQuery ? hits.map((hit) => hit.entry) : listQuery.data?.items ?? []

  const selected = entries.find((entry) => entry.id === selectedId) ?? null

  const invalidateKb = () => {
    void queryClient.invalidateQueries({ queryKey: ['kb'] })
    void queryClient.invalidateQueries({ queryKey: ['kb-search'] })
  }

  const saveMutation = useMutation({
    mutationFn: (value: DraftEntry) => {
      const payload = {
        game: value.game.trim() || undefined,
        title: value.title.trim(),
        description: value.description.trim() || undefined,
        script: value.script,
        tags: value.tags
          .split(',')
          .map((tag) => tag.trim())
          .filter(Boolean),
        source: 'manual',
        enabled: true,
      }
      return value.id ? api.kb.update(value.id, payload) : api.kb.create(payload)
    },
    onSuccess: (entry) => {
      invalidateKb()
      setDraft(null)
      setSelectedId(entry.id)
      notify({ tone: 'info', title: '条目已保存', hint: '可以点「复制到我的脚本库」直接变成可运行的脚本。' })
    },
    onError: (error) => reportError('保存知识库条目失败', error),
  })

  const deleteMutation = useMutation({
    mutationFn: (entryId: string) => api.kb.remove(entryId),
    onSuccess: (_result, entryId) => {
      invalidateKb()
      setConfirmingId(null)
      if (selectedId === entryId) setSelectedId(null)
      notify({ tone: 'info', title: '条目已删除' })
    },
    onError: (error) => reportError('删除条目失败', error),
  })

  const copyToScripts = useMutation({
    mutationFn: (entry: KnowledgeEntry) =>
      api.scripts.create({
        name: entry.title || '来自知识库的脚本',
        description: entry.description?.trim() || `来自知识库：${entry.title}`,
        source: entry.script,
      }),
    onSuccess: (script) => {
      void queryClient.invalidateQueries({ queryKey: ['scripts'] })
      selectScript(script.id)
      notify({ tone: 'info', title: `已复制到脚本库：${script.name}`, hint: '已经打开在中间编辑器，检查后 Ctrl+S 保存。' })
    },
    onError: (error) => reportError('复制到脚本库失败', error),
  })

  const submitDraft = () => {
    if (!draft) return
    if (!draft.title.trim()) {
      notify({ tone: 'warn', title: '标题不能为空', hint: '写一句能认出用途的标题，例如 连点器骨架。' })
      return
    }
    if (!draft.script.trim()) {
      notify({ tone: 'warn', title: 'Lua 正文不能为空', hint: '贴一段代码，空条目搜不到也用不上。' })
      return
    }
    saveMutation.mutate(draft)
  }

  return (
    <div className="panel" style={{ height: '100%' }}>
      <div className="kb-search">
        <input
          className="input"
          type="search"
          value={keyword}
          placeholder="搜索知识库"
          onChange={(event) => setKeyword(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter') setSubmittedQuery(keyword.trim())
          }}
        />
        <button type="button" className="btn btn-secondary" onClick={() => setSubmittedQuery(keyword.trim())}>
          搜索
        </button>
        <button type="button" className="btn btn-secondary" onClick={() => setDraft({ ...EMPTY_DRAFT })}>
          新建
        </button>
      </div>

      <div className="kb-mode">
        <span>匹配方式</span>
        <button
          type="button"
          className={`console-filter ${mode === 'keyword' ? 'console-filter-active' : ''}`}
          onClick={() => setMode('keyword')}
          title="子串与标签匹配，本地即时返回"
        >
          关键词
        </button>
        <button
          type="button"
          className={`console-filter ${mode === 'auto' ? 'console-filter-active' : ''}`}
          onClick={() => setMode('auto')}
          title="有向量检索就用，没有自动降级为关键词"
        >
          自动
        </button>
        <span className="spacer" />
        {submittedQuery ? (
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={() => {
              setSubmittedQuery('')
              setKeyword('')
            }}
          >
            返回全部
          </button>
        ) : null}
      </div>

      {submittedQuery ? (
        <div className="kb-hits-note">
          「{submittedQuery}」命中 {hits.length} 条
          {searchQuery.isFetching ? ' · 搜索中' : ''}
        </div>
      ) : null}

      <div className="panel-body">
        {selected ? (
          <div className="kb-detail">
            <div className="kb-detail-title">{selected.title}</div>
            {selected.game || (selected.tags && selected.tags.length > 0) ? (
              <div className="kb-tags">
                {selected.game ? <span className="kb-tag">{selected.game}</span> : null}
                {(selected.tags ?? []).map((tag) => (
                  <span className="kb-tag" key={tag}>
                    {tag}
                  </span>
                ))}
              </div>
            ) : null}
            {selected.description ? <div className="text-2">{selected.description}</div> : null}
            <div className="text-3" style={{ fontSize: 12 }}>
              更新于 {formatListTime(selected.updatedAt)}
            </div>
            <pre className="kb-detail-content">{selected.script || '（正文为空）'}</pre>
            <div className="kb-detail-actions">
              <button
                type="button"
                className="btn btn-primary"
                disabled={copyToScripts.isPending || !selected.script.trim()}
                onClick={() => copyToScripts.mutate(selected)}
              >
                复制到我的脚本库
              </button>
              <button type="button" className="btn btn-secondary" onClick={() => setDraft(toDraft(selected))}>
                编辑
              </button>
              {confirmingId === selected.id ? (
                <>
                  <button
                    type="button"
                    className="btn btn-danger-ghost"
                    disabled={deleteMutation.isPending}
                    onClick={() => deleteMutation.mutate(selected.id)}
                  >
                    确认删除
                  </button>
                  <button type="button" className="btn btn-ghost" onClick={() => setConfirmingId(null)}>
                    取消
                  </button>
                </>
              ) : (
                <button type="button" className="btn btn-danger-ghost" onClick={() => setConfirmingId(selected.id)}>
                  删除
                </button>
              )}
              <button type="button" className="btn btn-ghost" onClick={() => setSelectedId(null)}>
                返回列表
              </button>
            </div>
          </div>
        ) : (
          <>
            {listQuery.isPending && !submittedQuery ? <div className="console-empty">正在读取知识库…</div> : null}
            {listQuery.isError && !submittedQuery ? (
              <div className="banner banner-danger" style={{ borderBottom: 'none' }}>
                <div className="banner-body">
                  <div className="banner-title">知识库读取失败</div>
                  <div>后端没有返回 /api/kb 的结果。</div>
                  <button
                    type="button"
                    className="btn btn-secondary btn-sm"
                    style={{ marginTop: 4 }}
                    onClick={() => void listQuery.refetch()}
                  >
                    重试
                  </button>
                </div>
              </div>
            ) : null}
            {searchQuery.isError && submittedQuery ? (
              <div className="banner banner-danger" style={{ borderBottom: 'none' }}>
                搜索失败：{searchQuery.error instanceof Error ? searchQuery.error.message : '未知原因'}
              </div>
            ) : null}
            {!listQuery.isPending && !searchQuery.isFetching && entries.length === 0 ? (
              <div className="empty">
                <div className="empty-title">{submittedQuery ? '没有命中条目' : '知识库是空的'}</div>
                <div className="empty-desc">
                  {submittedQuery
                    ? '换一个关键词，或切到「自动」模式再搜一次。'
                    : '把常用代码片段、坐标写法存进来，写脚本时可以直接复用。'}
                </div>
                <button type="button" className="btn btn-secondary empty-action" onClick={() => setDraft({ ...EMPTY_DRAFT })}>
                  新建条目
                </button>
              </div>
            ) : null}

            {entries.map((entry) => {
              const hit = hits.find((item) => item.entry.id === entry.id)
              return (
                <div
                  key={entry.id}
                  className="kb-item"
                  role="button"
                  tabIndex={0}
                  onClick={() => setSelectedId(entry.id)}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter') setSelectedId(entry.id)
                  }}
                >
                  <div className="kb-item-head">
                    <span className="kb-item-title">{entry.title}</span>
                    {hit ? <span className="kb-item-score">{hit.score.toFixed(2)}</span> : null}
                    <span className="kb-item-score">{formatListTime(entry.updatedAt)}</span>
                  </div>
                  <div className="kb-item-snippet">{hit?.snippet ? truncate(hit.snippet, 90) : summaryOf(entry)}</div>
                </div>
              )
            })}
          </>
        )}
      </div>

      {draft ? (
        <KbEditorDialog
          draft={draft}
          saving={saveMutation.isPending}
          onChange={setDraft}
          onCancel={() => setDraft(null)}
          onSubmit={submitDraft}
        />
      ) : null}
    </div>
  )
}
