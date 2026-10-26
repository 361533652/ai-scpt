/**
 * 热键捕获与规范化。
 * 契约：accelerator 形如 `ctrl+shift+f8`、`f8`、`mouse4`，全小写，
 * 修饰键顺序固定为 ctrl+alt+shift+meta。
 * 鼠标键号按 ADR-0001（H7）：1=左、2=中、3=右、4/5=侧键；
 * 浏览器的 MouseEvent.button 是 0=左、1=中、2=右、3=后退、4=前进，所以统一 +1。
 */

export const MODIFIER_ORDER = ['ctrl', 'alt', 'shift', 'meta'] as const
export type Modifier = (typeof MODIFIER_ORDER)[number]

const MODIFIER_SET = new Set<string>(MODIFIER_ORDER)

const SPECIAL_KEYS: Record<string, string> = {
  ' ': 'space',
  Spacebar: 'space',
  Escape: 'escape',
  Esc: 'escape',
  Enter: 'enter',
  Tab: 'tab',
  Backspace: 'backspace',
  Delete: 'delete',
  Insert: 'insert',
  ArrowUp: 'up',
  ArrowDown: 'down',
  ArrowLeft: 'left',
  ArrowRight: 'right',
  Home: 'home',
  End: 'end',
  PageUp: 'pageup',
  PageDown: 'pagedown',
  CapsLock: 'caps_lock',
  NumLock: 'num_lock',
  ScrollLock: 'scroll_lock',
  PrintScreen: 'printscreen',
  Pause: 'pause',
  ContextMenu: 'context_menu',
  Control: 'ctrl',
  Alt: 'alt',
  Shift: 'shift',
  Meta: 'meta',
  AltGraph: 'alt_graph',
  '`': 'backquote',
  '~': 'backquote',
  '-': 'minus',
  _: 'minus',
  '=': 'equals',
  '+': 'equals',
  '[': 'open_bracket',
  '{': 'open_bracket',
  ']': 'close_bracket',
  '}': 'close_bracket',
  '\\': 'back_slash',
  '|': 'back_slash',
  ';': 'semicolon',
  ':': 'semicolon',
  "'": 'quote',
  '"': 'quote',
  ',': 'comma',
  '<': 'comma',
  '.': 'period',
  '>': 'period',
  '/': 'slash',
  '?': 'slash',
}

const NUMPAD_KEYS: Record<string, string> = {
  NumpadAdd: 'add',
  NumpadSubtract: 'subtract',
  NumpadMultiply: 'multiply',
  NumpadDivide: 'divide',
  NumpadDecimal: 'decimal',
  NumpadEnter: 'enter',
  NumpadEqual: 'equals',
}

const ACCELERATOR_PATTERN =
  /^(?:(?:ctrl|alt|shift|meta)\+)*(?:[a-z0-9_]+|f(?:[1-9]|1[0-9]|2[0-4])|mouse[1-9])$/

const MODIFIER_LABEL: Record<string, string> = {
  ctrl: 'Ctrl',
  alt: 'Alt',
  shift: 'Shift',
  meta: 'Win',
}

const KEY_LABEL: Record<string, string> = {
  space: '空格',
  escape: 'Esc',
  enter: '回车',
  tab: 'Tab',
  backspace: '退格',
  delete: 'Del',
  insert: 'Ins',
  up: '↑',
  down: '↓',
  left: '←',
  right: '→',
  pageup: 'PgUp',
  pagedown: 'PgDn',
  backquote: '`',
  minus: '-',
  equals: '=',
  open_bracket: '[',
  close_bracket: ']',
  back_slash: '\\',
  semicolon: ';',
  quote: "'",
  comma: ',',
  period: '.',
  slash: '/',
}

function baseKeyFromEvent(event: KeyboardEvent): string | null {
  const code = event.code ?? ''

  if (code.startsWith('Numpad')) {
    if (NUMPAD_KEYS[code]) return NUMPAD_KEYS[code]
    const digit = /^Numpad(\d)$/.exec(code)
    if (digit) return `num${digit[1]}`
    return null
  }

  const key = event.key
  if (!key || key === 'Unidentified' || key === 'Dead' || key === 'Process') return null

  if (/^F\d{1,2}$/i.test(key)) {
    const index = Number(key.slice(1))
    return index >= 1 && index <= 24 ? `f${index}` : null
  }
  if (SPECIAL_KEYS[key]) return SPECIAL_KEYS[key]
  if (/^[a-zA-Z]$/.test(key)) return key.toLowerCase()
  if (/^[0-9]$/.test(key)) return key
  return null
}

/** 把一次 keydown 事件转成契约要求的 accelerator；无法映射时返回 null。 */
export function normalizeKeyEvent(event: KeyboardEvent): string | null {
  const base = baseKeyFromEvent(event)
  if (!base) return null
  if (MODIFIER_SET.has(base)) return base

  const parts: string[] = []
  if (event.ctrlKey) parts.push('ctrl')
  if (event.altKey) parts.push('alt')
  if (event.shiftKey) parts.push('shift')
  if (event.metaKey) parts.push('meta')
  parts.push(base)
  return parts.join('+')
}

/** 鼠标键：MouseEvent.button → mouse1..mouse5。 */
export function normalizeMouseEvent(event: MouseEvent): string {
  const index = Math.min(Math.max(event.button, 0), 4) + 1
  return `mouse${index}`
}

/** 判断是否还在按鼠标键（侧键在部分鼠标上是前进/后退）。 */
export function isCapturableMouseButton(button: number): boolean {
  return button >= 0 && button <= 4
}

function sortModifiers(parts: string[]): string[] {
  const mods = parts.filter((part) => MODIFIER_SET.has(part))
  const rest = parts.filter((part) => !MODIFIER_SET.has(part))
  const ordered = MODIFIER_ORDER.filter((mod) => mods.includes(mod))
  return [...ordered, ...rest]
}

/** 校验并规范化用户手打的组合键文本。非法返回 null。 */
export function parseAccelerator(raw: string): string | null {
  const parts = raw
    .split('+')
    .map((part) => part.trim().toLowerCase())
    .filter(Boolean)
  if (parts.length === 0) return null
  const sorted = sortModifiers(parts)
  const value = sorted.join('+')
  return ACCELERATOR_PATTERN.test(value) ? value : null
}

export function isValidAccelerator(value: string): boolean {
  if (!ACCELERATOR_PATTERN.test(value)) return false
  const parts = value.split('+')
  const mods = parts.filter((part) => MODIFIER_SET.has(part))
  const expected = MODIFIER_ORDER.filter((mod) => mods.includes(mod)).join('+')
  return mods.join('+') === expected
}

/** accelerator → 界面文案，例如 ctrl+shift+f8 → Ctrl+Shift+F8，mouse4 → 鼠标 4 键。 */
export function describeAccelerator(value: string): string {
  if (!value) return '未绑定'
  const parts = value.split('+')
  const mouse = parts.length === 1 ? /^mouse(\d)$/.exec(parts[0]) : null
  if (mouse) return `鼠标 ${mouse[1]} 键`

  return parts
    .map((part) => {
      if (MODIFIER_LABEL[part]) return MODIFIER_LABEL[part]
      if (KEY_LABEL[part]) return KEY_LABEL[part]
      if (/^f\d{1,2}$/.test(part)) return part.toUpperCase()
      if (/^num\d$/.test(part)) return `小键盘 ${part.slice(3)}`
      if (part.length === 1) return part.toUpperCase()
      return part
    })
    .join('+')
}
