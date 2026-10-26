import { useEffect } from 'react'
import { setUnauthorizedHandler } from './api/client'
import { ConsolePanel } from './components/ConsolePanel'
import { EditorPane } from './components/EditorPane'
import { LuaHelpDialog } from './components/LuaHelpDialog'
import { NoticeStack } from './components/NoticeStack'
import { ScriptLibrary } from './components/ScriptLibrary'
import { SettingsDialog } from './components/SettingsDialog'
import { SidePanel } from './components/SidePanel'
import { Splitter } from './components/Splitter'
import { TokenGate } from './components/TokenGate'
import { TopBar } from './components/TopBar'
import { useUiStore } from './store/ui'

function Workspace() {
  const leftWidth = useUiStore((state) => state.leftWidth)
  const rightWidth = useUiStore((state) => state.rightWidth)
  const consoleHeight = useUiStore((state) => state.consoleHeight)
  const consoleCollapsed = useUiStore((state) => state.consoleCollapsed)
  const setLeftWidth = useUiStore((state) => state.setLeftWidth)
  const setRightWidth = useUiStore((state) => state.setRightWidth)
  const setConsoleHeight = useUiStore((state) => state.setConsoleHeight)

  return (
    <div className="app">
      <TopBar />
      <div className="app-body">
        <div className="pane-left" style={{ width: leftWidth }}>
          <ScriptLibrary />
        </div>
        <Splitter
          orientation="vertical"
          title="拖动调整脚本库宽度"
          onResize={(delta) => setLeftWidth(leftWidth + delta)}
        />
        <div className="pane-center">
          <EditorPane />
        </div>
        <Splitter
          orientation="vertical"
          title="拖动调整右栏宽度"
          onResize={(delta) => setRightWidth(rightWidth - delta)}
        />
        <div className="pane-right" style={{ width: rightWidth }}>
          <SidePanel />
        </div>
      </div>
      <div className="console-dock" style={{ height: consoleCollapsed ? 32 : consoleHeight }}>
        {consoleCollapsed ? null : (
          <Splitter
            orientation="horizontal"
            title="拖动调整控制台高度"
            onResize={(delta) => setConsoleHeight(consoleHeight - delta)}
          />
        )}
        <ConsolePanel />
      </div>
      <SettingsDialog />
      <LuaHelpDialog />
      <NoticeStack />
    </div>
  )
}

export default function App() {
  const token = useUiStore((state) => state.token)
  const dropToken = useUiStore((state) => state.dropToken)

  useEffect(() => {
    // 后端返回 401 时不再重试，直接回到令牌提示屏，避免白屏或死循环请求。
    setUnauthorizedHandler(() => dropToken())
    return () => setUnauthorizedHandler(null)
  }, [dropToken])

  if (!token) return <TokenGate />
  return <Workspace />
}
