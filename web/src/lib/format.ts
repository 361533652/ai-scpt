/** 时间与文本格式化。日志与路径一律等宽由 CSS 负责，这里只管内容。 */

const LEVEL_LABEL: Record<string, string> = {
  DEBUG: '调试',
  INFO: '信息',
  WARN: '警告',
  ERROR: '错误',
}

export function levelLabel(level: string): string {
  return LEVEL_LABEL[level] ?? level
}

function pad(value: number, width = 2): string {
  return String(value).padStart(width, '0')
}

/**
 * 控制台时间戳：HH:mm:ss。
 * 规范要求时间戳列固定 68px，13px 等宽下一行只放得下 8 个字符，
 * 带毫秒会折行，所以毫秒只在悬停提示和导出 .log 里出现。
 */
export function formatLogTime(timeMillis: number): string {
  if (!Number.isFinite(timeMillis)) return '--:--:--'
  const date = new Date(timeMillis)
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 带毫秒的完整时间，用于导出 .log 与悬停提示。 */
export function formatLogTimeFull(timeMillis: number): string {
  if (!Number.isFinite(timeMillis)) return '--:--:--.---'
  const date = new Date(timeMillis)
  return `${formatLogTime(timeMillis)}.${pad(date.getMilliseconds(), 3)}`
}

export function formatClock(date: Date): string {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

/** 运行时长：00:12:34。 */
export function formatDuration(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const seconds = total % 60
  return `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`
}

/** 短时长："12s" / "3m20s"。 */
export function formatShortDuration(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000))
  if (total < 60) return `${total}s`
  const minutes = Math.floor(total / 60)
  const seconds = total % 60
  return seconds === 0 ? `${minutes}m` : `${minutes}m${seconds}s`
}

export function parseTime(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined) return null
  if (typeof value === 'number') return Number.isFinite(value) ? value : null
  // 后端 RunService 的 startedAt 是 epoch millis 的 JSON 数字，
  // 但脚本/知识库里的时间是 ISO 字符串；两种都要能吃。
  const numeric = Number(value)
  if (value.trim() !== '' && Number.isFinite(numeric) && /^\d+$/.test(value.trim())) {
    return numeric
  }
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? null : parsed
}

/** 列表项右侧的修改时间：今天给 HH:mm，昨天给「昨天」，更早给 YYYY-MM-DD。 */
export function formatListTime(value: string | null | undefined, now = Date.now()): string {
  const time = parseTime(value)
  if (time === null) return '—'
  const date = new Date(time)
  const today = new Date(now)
  const startOfToday = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime()
  if (time >= startOfToday) return formatClock(date)
  if (time >= startOfToday - 86_400_000) return '昨天'
  const sameYear = date.getFullYear() === today.getFullYear()
  const month = pad(date.getMonth() + 1)
  const day = pad(date.getDate())
  return sameYear ? `${month}-${day}` : `${date.getFullYear()}-${month}-${day}`
}

export function truncate(text: string, max: number): string {
  const flat = text.replace(/\s+/g, ' ').trim()
  return flat.length <= max ? flat : `${flat.slice(0, max - 1)}…`
}

export function basename(path: string): string {
  const parts = path.split(/[\\/]/)
  return parts[parts.length - 1] || path
}

/** 导出文件名：脚本名里的非法字符换成下划线。 */
export function safeFileName(name: string, extension: string): string {
  const cleaned = name.replace(/[\\/:*?"<>|]+/g, '_').trim() || 'script'
  return cleaned.toLowerCase().endsWith(extension) ? cleaned : `${cleaned}${extension}`
}
