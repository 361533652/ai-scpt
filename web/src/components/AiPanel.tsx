import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import type { AiModelRef, AiProvider, ChatSession } from '../api/types'
import { Markdown } from './Markdown'
import { useChatStream } from '../hooks/useChatStream'
import { useHealth } from '../hooks/useHealth'
import { useErrorNotice } from '../hooks/useNotice'
import { useEditorStore } from '../store/editor'
import { useUiStore } from '../store/ui'
import { formatListTime, truncate } from '../lib/format'

function modelName(ref: AiModelRef): string {
  if (typeof ref === 'string') return ref
  return ref.name ?? ref.model ?? ref.id ?? ''
}

function sessionTitle(session: ChatSession): string {
  const title = session.title?.trim()
  if (title) return truncate(title, 40)
  return `会话 ${session.id.slice(0, 8)}`
}

export function AiPanel() {
  const queryClient = useQueryClient()
  const notify = useUiStore((state) => state.notify)
  const setSettingsOpen = useUiStore((state) => state.setSettingsOpen)
  const reportError = useErrorNotice()

  const health = useHealth()
  const chat = useChatStream()
  const scriptId = useEditorStore((state) => state.scriptId)
  const scriptName = useEditorStore((state) => state.name)
  const dirty = useEditorStore((state) => state.dirty)

  const [providerId, setProviderId] = useState('')
  const [model, setModel] = useState('')
  const [message, setMessage] = useState('')
  const [historyOpen, setHistoryOpen] = useState(false)
  const [includeScript, setIncludeScript] = useState(true)
  const stickRef = useRef(true)
  const scrollRef = useRef<HTMLDivElement | null>(null)

  const providersQuery = useQuery({
    queryKey: ['ai-providers'],
    queryFn: api.ai.providers,
    staleTime: 30_000,
  })

  const providers: AiProvider[] = useMemo(() => providersQuery.data?.items ?? [], [providersQuery.data])

  useEffect(() => {
    if (providers.length === 0) return
    if (providerId && providers.some((provider) => provider.id === providerId)) return
    const preferred = providers.find((provider) => provider.available) ?? providers[0]
    setProviderId(preferred.id)
    setModel(preferred.model ?? '')
  }, [providers, providerId])

  const modelsQuery = useQuery({
    queryKey: ['ai-models', providerId],
    queryFn: () => api.ai.models(providerId),
    enabled: Boolean(providerId),
    staleTime: 60_000,
  })

  const models = useMemo(
    () => (modelsQuery.data?.models ?? []).map(modelName).filter((name) => name.length > 0),
    [modelsQuery.data],
  )

  const sessionsQuery = useQuery({
    queryKey: ['ai-sessions'],
    queryFn: api.ai.sessions,
    enabled: historyOpen,
    staleTime: 5_000,
  })

  const deleteSession = useMutation({
    mutationFn: (sessionIdValue: string) => api.ai.deleteSession(sessionIdValue),
    onSuccess: (_result, sessionIdValue) => {
      void queryClient.invalidateQueries({ queryKey: ['ai-sessions'] })
      if (chat.sessionId === sessionIdValue) chat.reset()
    },
    onError: (error) => reportError('删除会话失败', error),
  })

  const activeProvider = providers.find((provider) => provider.id === providerId)
  const ollama = health.data?.ollama
  const offline = Boolean(activeProvider && !activeProvider.available)
  const canSend = Boolean(providerId) && message.trim().length > 0 && !chat.streaming && !offline

  // 消息流跟随滚动：只有贴底时才自动跟随，用户往上翻就不打扰。
  useEffect(() => {
    const container = scrollRef.current
    if (!container || !stickRef.current) return
    container.scrollTop = container.scrollHeight
  }, [chat.turns])

  const handleSend = async () => {
    if (!canSend) return
    const text = message
    setMessage('')
    stickRef.current = true
    await chat.send({
      message: text,
      providerId,
      model: model || activeProvider?.model,
      scriptId: scriptId ?? undefined,
      includeScript: scriptId ? includeScript : false,
    })
  }

  const handleStop = async () => {
    const result = await chat.stop()
    if (!result.ok && result.error) {
      notify({
        tone: 'warn',
        title: '停止请求没有生效',
        detail: result.error.reason,
        hint: result.error.nextStep,
      })
    }
  }

  const handleLoadSession = async (session: ChatSession) => {
    const result = await chat.loadHistory(session.id)
    if (!result.ok && result.error) {
      notify({ tone: 'error', title: '载入会话失败', detail: result.error.reason, hint: result.error.nextStep })
      return
    }
    setHistoryOpen(false)
    stickRef.current = true
  }

  return (
    <div className="panel" style={{ height: '100%' }}>
      <div className="chat-connect">
        <select
          className="select"
          value={providerId}
          onChange={(event) => setProviderId(event.target.value)}
          title="AI 提供方"
          disabled={providers.length === 0}
        >
          {providers.length === 0 ? <option value="">正在读取通道…</option> : null}
          {providers.map((provider) => (
            <option key={provider.id} value={provider.id}>
              {/* 不可用要写清「为什么」，否则用户只会看到「没有可用的提供方」却无从下手。
                  远端通道未配置密钥时是「未配置」而不是「故障」，措辞要区分开。 */}
              {provider.id}
              {provider.available
                ? ''
                : provider.probed === false
                  ? '（未配置密钥）'
                  : '（连接失败，检查服务是否启动）'}
            </option>
          ))}
        </select>
        <select className="select" value={model} onChange={(event) => setModel(event.target.value)} title="模型">
          {models.length === 0 ? <option value={model}>{model || '默认模型'}</option> : null}
          {models.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          aria-pressed={historyOpen}
          title="历史会话"
          onClick={() => setHistoryOpen((value) => !value)}
        >
          历史
        </button>
      </div>

      {offline ? (
        <div className="chat-offline">
          <span className="chat-offline-text">
            Ollama 未连接 {ollama?.baseUrl ?? '127.0.0.1:11434'}，发送已停用
          </span>
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => void health.refetch()}>
            重试
          </button>
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => setSettingsOpen(true)}>
            设置
          </button>
        </div>
      ) : null}

      {historyOpen ? (
        <div className="chat-history">
          {sessionsQuery.isPending ? <div className="console-empty">正在读取会话…</div> : null}
          {sessionsQuery.isError ? <div className="console-empty">会话列表读取失败，稍后重试。</div> : null}
          {sessionsQuery.data?.items?.length === 0 ? <div className="console-empty">还没有历史会话。</div> : null}
          {(sessionsQuery.data?.items ?? []).map((session) => (
            <div
              key={session.id}
              className="chat-history-item"
              role="button"
              tabIndex={0}
              onClick={() => void handleLoadSession(session)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void handleLoadSession(session)
              }}
            >
              <span className="chat-history-title">{sessionTitle(session)}</span>
              <span className="chat-history-time">{formatListTime(session.updatedAt)}</span>
              <button
                type="button"
                className="btn btn-danger-ghost btn-sm"
                disabled={deleteSession.isPending}
                onClick={(event) => {
                  event.stopPropagation()
                  deleteSession.mutate(session.id)
                }}
              >
                删除
              </button>
            </div>
          ))}
        </div>
      ) : null}

      <div
        className="chat-messages"
        ref={scrollRef}
        onScroll={(event) => {
          const target = event.currentTarget
          stickRef.current = target.scrollHeight - target.scrollTop - target.clientHeight < 40
        }}
      >
        {chat.turns.length === 0 ? (
          <div className="empty">
            <div className="empty-title">还没开始对话</div>
            <div className="empty-desc">
              描述想做的事，例如：按住左键不放时循环点右键，松开就停。
              {scriptId ? ' 勾选下面的「带上当前脚本」，助手会看到编辑器里的内容。' : ''}
            </div>
          </div>
        ) : null}

        {chat.turns.map((turn) => (
          <div key={turn.id} className={`chat-turn chat-turn-${turn.role}`}>
            <div className="chat-role">{turn.role === 'user' ? '你' : '助手'}</div>

            {turn.role === 'assistant' && turn.thinking ? (
              <details className="chat-thinking">
                <summary>思考过程（{turn.thinking.length} 字）</summary>
                <div className="chat-thinking-body">{turn.thinking}</div>
              </details>
            ) : null}

            <div className={`chat-bubble ${turn.role === 'user' ? 'chat-bubble-user' : 'chat-bubble-assistant'}`}>
              {turn.role === 'user' ? (
                turn.content
              ) : (
                <>
                  {turn.content ? <Markdown content={turn.content} /> : null}
                  {turn.status === 'streaming' ? <span className="chat-cursor" /> : null}
                  {!turn.content && turn.status === 'streaming' && !turn.thinking ? (
                    <span className="text-3">等待模型返回…</span>
                  ) : null}
                </>
              )}
            </div>

            {turn.error ? (
              <div className="chat-error">
                <div className="chat-error-reason">{turn.error.reason}</div>
                <div className="chat-error-hint">{turn.error.nextStep}</div>
                <div className="chat-meta">错误码 {turn.error.code}</div>
              </div>
            ) : null}

            {turn.role === 'assistant' && (turn.model || turn.usage) ? (
              <div className="chat-meta">
                {turn.model ? <span>{turn.model}</span> : null}
                {turn.usage ? (
                  <span>
                    输入 {turn.usage.tokensIn} / 输出 {turn.usage.tokensOut} tokens
                  </span>
                ) : null}
                {turn.status === 'stopped' ? <span>已停止</span> : null}
              </div>
            ) : null}
          </div>
        ))}
      </div>

      <div className="chat-compose">
        <textarea
          className="textarea"
          value={message}
          placeholder="输入想要脚本做的事，例如：循环按 F 键 5 秒"
          onChange={(event) => setMessage(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              void handleSend()
            }
          }}
        />
        <div className="chat-compose-row">
          <label className="check">
            <input
              type="checkbox"
              checked={includeScript && Boolean(scriptId)}
              disabled={!scriptId}
              onChange={(event) => setIncludeScript(event.target.checked)}
            />
            带上当前脚本{scriptName ? `（${truncate(scriptName, 12)}）` : ''}
          </label>
          {dirty && includeScript ? <span className="chat-compose-hint">未保存的修改不会带上</span> : null}
          <span className="spacer" />
          {chat.streaming ? (
            <button type="button" className="btn btn-danger" onClick={() => void handleStop()}>
              停止
            </button>
          ) : (
            <button
              type="button"
              className="btn btn-primary"
              disabled={!canSend}
              title={offline ? '提供方不可用，先在设置里检查地址' : 'Enter 发送，Shift+Enter 换行'}
              onClick={() => void handleSend()}
            >
              发送
            </button>
          )}
        </div>
        <span className="chat-compose-hint">
          {offline
            ? '提供方不可用：确认服务已启动，或在设置里改地址。'
            : modelsQuery.isError
              ? '模型列表拉取失败，仍会用提供方的默认模型发送。'
              : 'Enter 发送，Shift+Enter 换行。'}
        </span>
      </div>
    </div>
  )
}
