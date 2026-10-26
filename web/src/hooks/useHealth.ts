import { useQuery } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import type { Health, HealthRunningEntry, RunningScript } from '../api/types'

export const HEALTH_QUERY_KEY = ['health'] as const

/** 状态栏数据源：10 秒一次，够反映钩子/运行态变化，又不会刷屏。 */
export function useHealth() {
  return useQuery({
    queryKey: HEALTH_QUERY_KEY,
    queryFn: api.health,
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
    retry: 1,
    staleTime: 5_000,
  })
}

/** health.running 的元素就是后端 RunService.ActiveRun，带上 scriptName 方便显示。 */
export function normalizeRunning(entries: HealthRunningEntry[] | undefined): RunningScript[] {
  if (!Array.isArray(entries)) return []
  return entries.map((entry) => {
    if (typeof entry === 'string') {
      return { scriptId: entry, label: entry, startedAt: null }
    }
    const started = entry.startedAt
    const startedAt =
      typeof started === 'number' ? started : typeof started === 'string' && started ? started : null
    return {
      scriptId: entry.scriptId,
      label: entry.scriptName?.trim() || entry.scriptId,
      startedAt,
    }
  })
}

export function hookState(health: Health | undefined): { text: string; tone: 'ok' | 'warn' | 'error' | 'idle'; detail: string } {
  if (!health) {
    return { text: '钩子状态未知', tone: 'idle', detail: '还未取到 /api/health 的响应。' }
  }
  if (health.hook?.registered) {
    return { text: '钩子已注册', tone: 'ok', detail: '全局键鼠钩子正在监听，热键可触发脚本。' }
  }
  const reason = health.hook?.error?.trim()
  return {
    text: '钩子未启用',
    tone: 'warn',
    detail: reason && reason.length > 0 ? reason : '后端未说明原因，检查后端日志里的钩子注册部分。',
  }
}
