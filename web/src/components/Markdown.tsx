import { useState, type ReactNode } from 'react'
import ReactMarkdown from 'react-markdown'
import { copyText } from '../lib/files'
import { getEditorBridge } from '../lib/editorBridge'
import { useUiStore } from '../store/ui'

interface CodeProps {
  className?: string
  children?: ReactNode
}

function CodeBlock({ language, text }: { language: string; text: string }) {
  const notify = useUiStore((state) => state.notify)
  const [copied, setCopied] = useState(false)
  const [confirmReplace, setConfirmReplace] = useState(false)

  const handleInsert = () => {
    const bridge = getEditorBridge()
    if (!bridge) {
      notify({
        tone: 'warn',
        title: '没有打开的编辑器',
        hint: '先在左侧脚本库里选一个脚本，再插入代码。',
      })
      return
    }
    bridge.insertAtCursor(text)
    notify({ tone: 'info', title: '已插入到光标处', hint: '记得 Ctrl+S 保存。' })
  }

  const handleReplace = () => {
    const bridge = getEditorBridge()
    if (!bridge) {
      notify({ tone: 'warn', title: '没有打开的编辑器', hint: '先在左侧脚本库里选一个脚本，再替换整段。' })
      return
    }
    if (!confirmReplace) {
      setConfirmReplace(true)
      return
    }
    bridge.replaceAll(text)
    setConfirmReplace(false)
    notify({ tone: 'info', title: '已替换整段代码', hint: '原内容被覆盖，Ctrl+Z 可以撤销，Ctrl+S 保存。' })
  }

  const handleCopy = async () => {
    const ok = await copyText(text)
    if (ok) {
      setCopied(true)
      window.setTimeout(() => setCopied(false), 1500)
    } else {
      notify({
        tone: 'warn',
        title: '复制失败',
        detail: '浏览器拒绝了剪贴板访问。',
        hint: '手动选中代码块内容复制，或检查浏览器的剪贴板权限。',
      })
    }
  }

  return (
    <div className="md-code">
      <div className="md-code-head">
        <span className="md-code-lang">{language || 'lua'}</span>
        <button type="button" className="btn btn-ghost btn-sm" onClick={handleInsert}>
          插入到光标处
        </button>
        <button
          type="button"
          className={`btn btn-sm ${confirmReplace ? 'btn-danger-ghost' : 'btn-ghost'}`}
          onClick={handleReplace}
          onBlur={() => setConfirmReplace(false)}
        >
          {confirmReplace ? '确认替换整段' : '整段替换'}
        </button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => void handleCopy()}>
          {copied ? '已复制' : '复制'}
        </button>
      </div>
      <pre className="md-code-pre">{text}</pre>
    </div>
  )
}

function PreRenderer({ children }: { children?: ReactNode }) {
  // 外层 pre 交给 CodeBlock 自己画，避免嵌套两层背景。
  return <>{children}</>
}

function CodeRenderer({ className, children }: CodeProps) {
  const text = String(children ?? '').replace(/\n$/, '')
  const language = /language-([\w-]+)/.exec(className ?? '')?.[1] ?? ''
  const isBlock = language.length > 0 || text.includes('\n')
  if (!isBlock) {
    return <code className="md-inline-code">{children}</code>
  }
  return <CodeBlock language={language} text={text} />
}

function AnchorRenderer({ href, children }: { href?: string; children?: ReactNode }) {
  return (
    <a href={href} target="_blank" rel="noreferrer noopener">
      {children}
    </a>
  )
}

/** 助手回答的 Markdown 渲染。不做 HTML 直插，全部走 react-markdown 的元素映射。 */
export function Markdown({ content }: { content: string }) {
  return (
    <div className="md">
      <ReactMarkdown
        components={{
          pre: PreRenderer,
          code: CodeRenderer,
          a: AnchorRenderer,
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  )
}
