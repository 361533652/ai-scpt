import { AiPanel } from './AiPanel'
import { KbPanel } from './KbPanel'
import { useUiStore } from '../store/ui'

/** 右栏：AI 对话 / 知识库 两个页签，尺寸由外层拖拽条控制。 */
export function SidePanel() {
  const tab = useUiStore((state) => state.rightTab)
  const setTab = useUiStore((state) => state.setRightTab)

  return (
    <div className="side-panel">
      <div className="side-tabs" role="tablist" aria-label="右栏">
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'chat'}
          className={`side-tab ${tab === 'chat' ? 'side-tab-active' : ''}`}
          onClick={() => setTab('chat')}
        >
          AI 对话
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === 'kb'}
          className={`side-tab ${tab === 'kb' ? 'side-tab-active' : ''}`}
          onClick={() => setTab('kb')}
        >
          知识库
        </button>
      </div>
      {tab === 'chat' ? <AiPanel /> : <KbPanel />}
    </div>
  )
}
