import { useUiStore } from '../store/ui'

/** 右下角通知堆叠。每条都带原因和下一步，关闭按钮在标题右侧。 */
export function NoticeStack() {
  const notices = useUiStore((state) => state.notices)
  const dismiss = useUiStore((state) => state.dismissNotice)

  if (notices.length === 0) return null

  return (
    <div className="notice-stack" role="status" aria-live="polite">
      {notices.map((notice) => (
        <div key={notice.id} className={`notice notice-${notice.tone}`}>
          <div className="notice-head">
            <span className="notice-title">{notice.title}</span>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => dismiss(notice.id)}>
              关闭
            </button>
          </div>
          {notice.detail ? <div className="notice-detail">{notice.detail}</div> : null}
          {notice.hint ? <div className="notice-hint">{notice.hint}</div> : null}
        </div>
      ))}
    </div>
  )
}
