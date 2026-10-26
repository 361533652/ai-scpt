import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import type { Settings, SettingsUpdate } from '../api/types'
import { AcceleratorInput } from './AcceleratorInput'
import { useErrorNotice } from '../hooks/useNotice'
import { useUiStore } from '../store/ui'

interface FormState {
  providerId: string
  ollamaBaseUrl: string
  ollamaModel: string
  cloudBaseUrl: string
  cloudModel: string
  cloudApiKey: string
  preciseRelativeMove: boolean
  eventQueueCapacity: number
  stopGraceMs: number
  emergencyHotkey: string
}

function toForm(settings: Settings): FormState {
  return {
    providerId: settings.providerId ?? '',
    ollamaBaseUrl: settings.ollamaBaseUrl ?? '',
    ollamaModel: settings.ollamaModel ?? '',
    cloudBaseUrl: settings.cloudBaseUrl ?? '',
    cloudModel: settings.cloudModel ?? '',
    cloudApiKey: '',
    preciseRelativeMove: Boolean(settings.preciseRelativeMove),
    eventQueueCapacity: settings.eventQueueCapacity ?? 0,
    stopGraceMs: settings.stopGraceMs ?? 0,
    emergencyHotkey: settings.emergencyHotkey ?? '',
  }
}

export function SettingsDialog() {
  const queryClient = useQueryClient()
  const open = useUiStore((state) => state.settingsOpen)
  const setOpen = useUiStore((state) => state.setSettingsOpen)
  const notify = useUiStore((state) => state.notify)
  const reportError = useErrorNotice()

  const settingsQuery = useQuery({
    queryKey: ['settings'],
    queryFn: api.settings.get,
    enabled: open,
    staleTime: 30_000,
  })

  const providersQuery = useQuery({
    queryKey: ['ai-providers'],
    queryFn: api.ai.providers,
    enabled: open,
    staleTime: 30_000,
  })

  const [form, setForm] = useState<FormState | null>(null)

  useEffect(() => {
    if (!open || !settingsQuery.data) return
    setForm(toForm(settingsQuery.data))
  }, [open, settingsQuery.data])

  const modelsQuery = useQuery({
    queryKey: ['ai-models', form?.providerId ?? ''],
    queryFn: () => api.ai.models(form?.providerId ?? ''),
    enabled: open && Boolean(form?.providerId),
    staleTime: 60_000,
  })

  const modelNames = (modelsQuery.data?.models ?? [])
    .map((item) => (typeof item === 'string' ? item : (item.name ?? item.model ?? item.id ?? '')))
    .filter((name) => name.length > 0)

  const saveMutation = useMutation({
    mutationFn: (value: FormState) => {
      const payload: SettingsUpdate = {
        providerId: value.providerId,
        ollamaBaseUrl: value.ollamaBaseUrl,
        ollamaModel: value.ollamaModel,
        cloudBaseUrl: value.cloudBaseUrl,
        cloudModel: value.cloudModel,
        preciseRelativeMove: value.preciseRelativeMove,
        eventQueueCapacity: Number(value.eventQueueCapacity) || 0,
        stopGraceMs: Number(value.stopGraceMs) || 0,
        emergencyHotkey: value.emergencyHotkey,
      }
      // apiKey 只进不出：只有用户这次真的填了才带上。
      if (value.cloudApiKey.trim()) payload.cloudApiKey = value.cloudApiKey.trim()
      return api.settings.put(payload)
    },
    onSuccess: (settings) => {
      queryClient.setQueryData(['settings'], settings)
      void queryClient.invalidateQueries({ queryKey: ['health'] })
      void queryClient.invalidateQueries({ queryKey: ['ai-providers'] })
      setOpen(false)
      notify({
        tone: 'info',
        title: '设置已保存',
        detail: `提供方 ${settings.providerId}`,
        hint: '急停热键改动会立即重注册钩子；Ollama 地址改动后状态栏会在下次刷新时反映。',
      })
    },
    onError: (error) => reportError('保存设置失败', error),
  })

  if (!open) return null

  const update = (patch: Partial<FormState>) => {
    setForm((prev) => (prev ? { ...prev, ...patch } : prev))
  }

  return (
    <div className="modal-backdrop">
      <div className="modal" style={{ maxWidth: 620 }}>
        <div className="modal-header">
          <span className="modal-title">设置</span>
          <button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>
            关闭
          </button>
        </div>

        <div className="modal-body">
          {settingsQuery.isError ? (
            <div className="banner banner-danger" style={{ borderBottom: 'none' }}>
              <div className="banner-body">
                <div className="banner-title">设置读取失败</div>
                <div>{settingsQuery.error instanceof Error ? settingsQuery.error.message : '后端没有返回 /api/settings。'}</div>
                <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 4 }} onClick={() => void settingsQuery.refetch()}>
                  重试
                </button>
              </div>
            </div>
          ) : null}

          {!form ? (
            <div className="text-2">正在读取设置…</div>
          ) : (
            <>
              <div className="modal-section">
                <div className="modal-section-title">AI 提供方</div>
                <div className="field">
                  <span className="field-label">当前提供方</span>
                  <select className="select" value={form.providerId} onChange={(event) => update({ providerId: event.target.value })}>
                    {providersQuery.data?.items?.length ? null : <option value={form.providerId}>{form.providerId || '（未知）'}</option>}
                    {(providersQuery.data?.items ?? []).map((provider) => (
                      <option key={provider.id} value={provider.id}>
                        {provider.id}（{provider.kind}
                        {provider.available ? '' : '，不可用'}）
                      </option>
                    ))}
                  </select>
                  <span className="field-hint">
                    {providersQuery.isError
                      ? '提供方列表读取失败，保留当前值；确认后端已启动。'
                      : '对话面板默认用这里的提供方，发消息前也可以临时切换。'}
                  </span>
                </div>
              </div>

              <div className="modal-section">
                <div className="modal-section-title">本地 Ollama</div>
                <div className="grid-2">
                  <label className="field">
                    <span className="field-label">服务地址</span>
                    <input
                      className="input"
                      value={form.ollamaBaseUrl}
                      placeholder="http://127.0.0.1:11434"
                      onChange={(event) => update({ ollamaBaseUrl: event.target.value })}
                    />
                  </label>
                  <label className="field">
                    <span className="field-label">默认模型</span>
                    <input
                      className="input"
                      list="gmscpt-ollama-models"
                      value={form.ollamaModel}
                      placeholder="qwen2.5-coder:7b"
                      onChange={(event) => update({ ollamaModel: event.target.value })}
                    />
                  </label>
                </div>
                <datalist id="gmscpt-ollama-models">
                  {modelNames.map((name) => (
                    <option key={name} value={name} />
                  ))}
                </datalist>
                <span className="field-hint">
                  {modelsQuery.isFetching
                    ? '正在从 /api/ai/models 拉模型列表…'
                    : modelNames.length > 0
                      ? `后端报告 ${modelNames.length} 个可用模型，输入框里可以直接选。`
                      : '模型列表为空或未取到；确认 Ollama 已启动，地址填的是它的监听端口。'}
                </span>
              </div>

              <div className="modal-section">
                <div className="modal-section-title">云端 OpenAI 兼容</div>
                <div className="grid-2">
                  <label className="field">
                    <span className="field-label">Base URL</span>
                    <input
                      className="input"
                      value={form.cloudBaseUrl}
                      placeholder="https://api.example.com/v1"
                      onChange={(event) => update({ cloudBaseUrl: event.target.value })}
                    />
                  </label>
                  <label className="field">
                    <span className="field-label">模型 ID</span>
                    <input
                      className="input"
                      value={form.cloudModel}
                      placeholder="gpt-4o-mini"
                      onChange={(event) => update({ cloudModel: event.target.value })}
                    />
                  </label>
                  <label className="field">
                    <span className="field-label">API Key</span>
                    <input
                      className="input"
                      type="password"
                      value={form.cloudApiKey}
                      placeholder={settingsQuery.data?.hasCloudApiKey ? '已保存，留空表示不改' : '还没有保存过'}
                      onChange={(event) => update({ cloudApiKey: event.target.value })}
                    />
                  </label>
                </div>
                <span className="field-hint">
                  读取接口只回 hasCloudApiKey（当前 {settingsQuery.data?.hasCloudApiKey ? '已保存' : '未保存'}），
                  API Key 只进不出，留空就不会覆盖已保存的值。
                </span>
              </div>

              <div className="modal-section">
                <div className="modal-section-title">运行与安全</div>
                <div className="field">
                  <span className="field-label">急停热键</span>
                  <AcceleratorInput
                    value={form.emergencyHotkey}
                    onChange={(accelerator) => update({ emergencyHotkey: accelerator })}
                    placeholder="录制急停热键"
                    hint="按下要用于急停鼠标注入的组合"
                  />
                  <span className="field-hint">脚本卡住时用这个组合立刻停掉全部运行；不要和脚本热键用同一个组合。</span>
                </div>
                <div className="grid-2">
                  <label className="field">
                    <span className="field-label">事件队列上限（条）</span>
                    <input
                      className="input"
                      type="number"
                      min={0}
                      value={form.eventQueueCapacity}
                      onChange={(event) => update({ eventQueueCapacity: Number(event.target.value) })}
                    />
                  </label>
                  <label className="field">
                    <span className="field-label">停止宽限（毫秒）</span>
                    <input
                      className="input"
                      type="number"
                      min={0}
                      value={form.stopGraceMs}
                      onChange={(event) => update({ stopGraceMs: Number(event.target.value) })}
                    />
                  </label>
                </div>
                <span className="field-hint">
                  队列满时丢最旧的输入事件并计数上报；停止宽限超时后脚本按 killed 处理。
                </span>
                <label className="check">
                  <input
                    type="checkbox"
                    checked={form.preciseRelativeMove}
                    onChange={(event) => update({ preciseRelativeMove: event.target.checked })}
                  />
                  相对移动使用高精度换算（会改系统鼠标设置，后端负责备份与还原）
                </label>
              </div>
            </>
          )}
        </div>

        <div className="modal-footer">
          <span className="spacer" />
          <button type="button" className="btn btn-secondary" onClick={() => setOpen(false)}>
            取消
          </button>
          <button
            type="button"
            className="btn btn-primary"
            disabled={!form || saveMutation.isPending}
            onClick={() => {
              if (form) saveMutation.mutate(form)
            }}
          >
            {saveMutation.isPending ? '保存中' : '保存设置'}
          </button>
        </div>
      </div>
    </div>
  )
}
