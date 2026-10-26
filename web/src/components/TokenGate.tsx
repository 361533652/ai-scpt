import { useState } from 'react'
import { api } from '../api/endpoints'
import { describeError, type ErrorDescription } from '../api/client'
import { clearToken, setToken as persistToken } from '../lib/token'
import { useUiStore } from '../store/ui'
import { BACKEND_LABEL } from '../api/client'

/**
 * token 缺失 / 失效时的整屏提示。
 * 契约：token 在服务启动时随机生成，打印在后端控制台，并以 ?token=xxx 打开浏览器。
 */
export function TokenGate() {
  const acceptToken = useUiStore((state) => state.setToken)
  const [draft, setDraft] = useState('')
  const [error, setError] = useState<ErrorDescription | null>(null)
  const [checking, setChecking] = useState(false)

  const verifyAndEnter = async () => {
    const value = draft.trim()
    if (!value) {
      setError({ code: 'EMPTY_TOKEN', reason: '还没有填入令牌。', nextStep: '从后端启动日志里复制 token 值再试。', traceId: null })
      return
    }
    setChecking(true)
    setError(null)
    persistToken(value)
    try {
      await api.health()
      acceptToken(value)
    } catch (caught) {
      clearToken()
      setError(describeError(caught))
    } finally {
      setChecking(false)
    }
  }

  return (
    <div className="gate">
      <div className="gate-card">
        <h1 className="gate-title">需要访问令牌</h1>
        <p className="gate-text">
          页面地址里没有 token 参数，浏览器里也没有存过。后端每次启动都会生成新的随机令牌，
          只监听 {BACKEND_LABEL}，前端必须带上它才能调用接口。
        </p>
        <ol className="gate-steps">
          <li>切到后端的控制台窗口，找到启动时打印的那行地址。</li>
          <li>它形如下面的样子，整条复制到浏览器打开即可自动进入。</li>
          <li>如果只想手工填，把 token= 后面的值粘到下面输入框。</li>
        </ol>
        <code className="gate-code">http://127.0.0.1:8790/?token=粘贴这里</code>
        <div className="gate-row">
          <label className="field" style={{ flex: '1 1 auto' }}>
            <span className="field-label">令牌</span>
            <input
              className="input"
              type="text"
              value={draft}
              spellCheck={false}
              autoComplete="off"
              placeholder="粘贴后端控制台打印的 token"
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Enter') void verifyAndEnter()
              }}
            />
          </label>
          <button type="button" className="btn btn-primary" disabled={checking} onClick={() => void verifyAndEnter()}>
            {checking ? '校验中' : '校验并进入'}
          </button>
        </div>
        {error ? (
          <div className="banner banner-danger" style={{ borderBottom: 'none', borderRadius: 4 }}>
            <div className="banner-body">
              <div className="banner-title">{error.reason}</div>
              <div>{error.nextStep}</div>
              <div className="notice-trace">错误码 {error.code}</div>
            </div>
          </div>
        ) : null}
      </div>
    </div>
  )
}
