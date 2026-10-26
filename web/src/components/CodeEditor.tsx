import { useEffect, useMemo, useRef } from 'react'
import { Compartment, EditorState, type Extension } from '@codemirror/state'
import {
  EditorView,
  crosshairCursor,
  drawSelection,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  keymap,
  lineNumbers,
  rectangularSelection,
} from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { HighlightStyle, StreamLanguage, bracketMatching, indentOnInput, syntaxHighlighting } from '@codemirror/language'
import { lua } from '@codemirror/legacy-modes/mode/lua'
import {
  autocompletion,
  closeBrackets,
  closeBracketsKeymap,
  completionKeymap,
  type CompletionContext,
  type CompletionResult,
} from '@codemirror/autocomplete'
import { lintGutter, setDiagnostics, type Diagnostic } from '@codemirror/lint'
import { tags as t } from '@lezer/highlight'
import type { ScriptDiagnostic } from '../api/types'
import type { CompletionItem } from '../hooks/useLuaApi'
import { setEditorBridge } from '../lib/editorBridge'

const luaTheme = EditorView.theme(
  {
    '&': {
      backgroundColor: 'var(--bg-sunken)',
      color: 'var(--text-1)',
      fontSize: 'var(--fs-14)',
      height: '100%',
    },
    '&.cm-focused': { outline: 'none' },
    '.cm-scroller': {
      fontFamily: 'var(--font-mono)',
      lineHeight: 'var(--lh-code)',
      overflow: 'auto',
    },
    '.cm-content': { padding: '8px 0', caretColor: 'var(--text-1)' },
    '.cm-gutters': {
      backgroundColor: 'var(--bg-sunken)',
      color: 'var(--text-3)',
      border: 'none',
      borderRight: '1px solid var(--border-subtle)',
    },
    '.cm-activeLine': { backgroundColor: 'rgba(214, 217, 222, 0.035)' },
    '.cm-activeLineGutter': { backgroundColor: 'var(--bg-raised)', color: 'var(--text-2)' },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: 'var(--text-1)' },
    '.cm-selectionBackground, .cm-content ::selection': {
      backgroundColor: 'rgba(74, 127, 193, 0.35)',
    },
    '.cm-panels': { backgroundColor: 'var(--bg-raised)', color: 'var(--text-1)' },
    '.cm-lintRange-error': { borderBottom: '1px wavy var(--danger)' },
  },
  { dark: true },
)

const luaHighlight = HighlightStyle.define([
  { tag: t.keyword, color: 'var(--syn-keyword)' },
  { tag: [t.string, t.special(t.string)], color: 'var(--syn-string)' },
  { tag: [t.number, t.bool, t.null], color: 'var(--syn-number)' },
  { tag: [t.comment, t.lineComment, t.blockComment], color: 'var(--syn-comment)', fontStyle: 'italic' },
  { tag: [t.function(t.variableName), t.function(t.propertyName)], color: 'var(--syn-api)' },
  { tag: [t.definition(t.variableName), t.variableName], color: 'var(--text-1)' },
  { tag: [t.propertyName, t.attributeName], color: 'var(--syn-number)' },
  { tag: [t.operator, t.punctuation, t.bracket], color: 'var(--text-2)' },
  { tag: t.typeName, color: 'var(--syn-api)' },
  { tag: t.invalid, color: 'var(--danger)' },
])

export interface CodeEditorProps {
  value: string
  diagnostics: ScriptDiagnostic[]
  completions: CompletionItem[]
  readOnly?: boolean
  onChange: (value: string) => void
  onSave: () => void
  onRun: () => void
  onCursorChange?: (line: number, column: number) => void
}

export function CodeEditor({
  value,
  diagnostics,
  completions,
  readOnly = false,
  onChange,
  onSave,
  onRun,
  onCursorChange,
}: CodeEditorProps) {
  const hostRef = useRef<HTMLDivElement | null>(null)
  const viewRef = useRef<EditorView | null>(null)
  const readOnlyCompartment = useRef(new Compartment())
  const applyingExternal = useRef(false)

  // 回调与补全候选都放 ref，避免每次渲染重建编辑器。
  const onChangeRef = useRef(onChange)
  const onSaveRef = useRef(onSave)
  const onRunRef = useRef(onRun)
  const onCursorRef = useRef(onCursorChange)
  const completionsRef = useRef(completions)

  onChangeRef.current = onChange
  onSaveRef.current = onSave
  onRunRef.current = onRun
  onCursorRef.current = onCursorChange
  completionsRef.current = completions

  const completionSource = useMemo(
    () =>
      (context: CompletionContext): CompletionResult | null => {
        const word = context.matchBefore(/[A-Za-z_][\w.]*/)
        if (!word && !context.explicit) return null
        if (word && word.from === word.to && !context.explicit) return null
        return {
          from: word ? word.from : context.pos,
          options: completionsRef.current.map((item) => ({
            label: item.label,
            type: item.type === 'function' ? 'function' : 'keyword',
            detail: item.detail.slice(0, 60),
            info: item.detail,
          })),
          validFor: /^[\w.]*$/,
        }
      },
    [],
  )

  useEffect(() => {
    const host = hostRef.current
    if (!host) return

    const extensions: Extension[] = [
      lineNumbers(),
      highlightActiveLineGutter(),
      highlightSpecialChars(),
      history(),
      drawSelection(),
      EditorState.allowMultipleSelections.of(true),
      indentOnInput(),
      bracketMatching(),
      closeBrackets(),
      autocompletion({
        override: [completionSource],
        activateOnTyping: true,
        closeOnBlur: true,
      }),
      rectangularSelection(),
      crosshairCursor(),
      highlightActiveLine(),
      lintGutter(),
      StreamLanguage.define(lua),
      syntaxHighlighting(luaHighlight),
      luaTheme,
      EditorView.lineWrapping,
      EditorView.updateListener.of((update) => {
        if (update.docChanged && !applyingExternal.current) {
          onChangeRef.current(update.state.doc.toString())
        }
        if (update.selectionSet || update.docChanged) {
          const head = update.state.selection.main.head
          const line = update.state.doc.lineAt(head)
          onCursorRef.current?.(line.number, head - line.from + 1)
        }
      }),
      readOnlyCompartment.current.of(EditorState.readOnly.of(readOnly)),
      keymap.of([
        {
          key: 'Mod-s',
          preventDefault: true,
          run: () => {
            onSaveRef.current()
            return true
          },
        },
        {
          key: 'F5',
          preventDefault: true,
          run: () => {
            onRunRef.current()
            return true
          },
        },
        ...closeBracketsKeymap,
        ...defaultKeymap,
        ...historyKeymap,
        ...completionKeymap,
        indentWithTab,
      ]),
    ]

    const view = new EditorView({
      state: EditorState.create({ doc: value, extensions }),
      parent: host,
    })
    viewRef.current = view

    setEditorBridge({
      insertAtCursor: (text) => {
        const current = viewRef.current
        if (!current) return
        const selection = current.state.selection.main
        current.dispatch({
          changes: { from: selection.from, to: selection.to, insert: text },
          selection: { anchor: selection.from + text.length },
          scrollIntoView: true,
        })
        current.focus()
      },
      replaceAll: (text) => {
        const current = viewRef.current
        if (!current) return
        const length = current.state.doc.length
        current.dispatch({
          changes: { from: 0, to: length, insert: text },
          selection: { anchor: text.length },
          scrollIntoView: true,
        })
        current.focus()
      },
      getDocument: () => viewRef.current?.state.doc.toString() ?? '',
      getSelection: () => {
        const current = viewRef.current
        if (!current) return ''
        const selection = current.state.selection.main
        return current.state.sliceDoc(selection.from, selection.to)
      },
      focus: () => viewRef.current?.focus(),
      jumpToLine: (lineNumber, column = 1) => {
        const current = viewRef.current
        if (!current) return
        const line = current.state.doc.line(Math.min(Math.max(lineNumber, 1), current.state.doc.lines))
        const position = Math.min(line.from + Math.max(column - 1, 0), line.to)
        current.dispatch({ selection: { anchor: position }, scrollIntoView: true })
        current.focus()
      },
    })

    return () => {
      setEditorBridge(null)
      view.destroy()
      viewRef.current = null
    }
    // 只建一次；value/readOnly 变化走下面的 effect。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [completionSource])

  // 外部改了正文（切换脚本、AI 整段替换、冲突后载入）：整篇替换，不触发 onChange。
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    const current = view.state.doc.toString()
    if (current === value) return
    applyingExternal.current = true
    view.dispatch({
      changes: { from: 0, to: current.length, insert: value },
      selection: { anchor: Math.min(view.state.selection.main.anchor, value.length) },
    })
    applyingExternal.current = false
  }, [value])

  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    view.dispatch({ effects: readOnlyCompartment.current.reconfigure(EditorState.readOnly.of(readOnly)) })
  }, [readOnly])

  // 校验结果标到行上。
  useEffect(() => {
    const view = viewRef.current
    if (!view) return
    const doc = view.state.doc
    const mapped: Diagnostic[] = diagnostics.map((item) => {
      const lineNumber = Math.min(Math.max(item.line || 1, 1), doc.lines)
      const line = doc.line(lineNumber)
      const columnOffset = Math.max((item.column || 1) - 1, 0)
      const from = Math.min(line.from + columnOffset, line.to)
      return {
        from,
        to: Math.max(line.to, from),
        severity: 'error',
        message: item.message,
        source: `第 ${lineNumber} 行`,
      }
    })
    view.dispatch(setDiagnostics(view.state, mapped))
  }, [diagnostics])

  return <div className="editor-host" ref={hostRef} />
}
