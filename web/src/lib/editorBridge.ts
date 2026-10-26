/**
 * 编辑器与其它面板之间的桥。
 * AI 回复里的代码块要「插入到光标处 / 整段替换」、诊断要「点击定位」，
 * 都要能拿到当前 CodeMirror 实例，但又不想把 editor view 塞进全局 store。
 */
export interface EditorBridge {
  insertAtCursor: (text: string) => void
  replaceAll: (text: string) => void
  getDocument: () => string
  getSelection: () => string
  focus: () => void
  jumpToLine: (line: number, column?: number) => void
}

let current: EditorBridge | null = null

export function setEditorBridge(bridge: EditorBridge | null): void {
  current = bridge
}

export function getEditorBridge(): EditorBridge | null {
  return current
}
