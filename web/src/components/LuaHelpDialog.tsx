import { useMemo, useState } from 'react'
import { luaItemDoc, luaItemName, luaItemSignature, useLuaApi } from '../hooks/useLuaApi'
import { useUiStore } from '../store/ui'

type Tab = 'functions' | 'keys' | 'events' | 'mouseButtons'

const TAB_LABEL: Record<Tab, string> = {
  functions: '函数',
  keys: '按键名',
  events: '事件',
  mouseButtons: '鼠标键',
}

/** Lua API 帮助。数据来自 /api/lua/api，与编辑器补全、AI 系统提示词同源。 */
export function LuaHelpDialog() {
  const open = useUiStore((state) => state.luaHelpOpen)
  const setOpen = useUiStore((state) => state.setLuaHelpOpen)
  const luaApi = useLuaApi()
  const [tab, setTab] = useState<Tab>('functions')
  const [keyword, setKeyword] = useState('')

  const functions = luaApi.data?.functions ?? []
  const filteredFunctions = useMemo(() => {
    const text = keyword.trim().toLowerCase()
    if (!text) return functions
    return functions.filter(
      (item) =>
        item.name.toLowerCase().includes(text) ||
        (item.signature ?? '').toLowerCase().includes(text) ||
        (item.doc ?? '').toLowerCase().includes(text),
    )
  }, [functions, keyword])

  const list = luaApi.data?.[tab] ?? []
  const filteredList = useMemo(() => {
    const text = keyword.trim().toLowerCase()
    if (!text) return list
    return list.filter((item) => `${luaItemName(item)} ${luaItemDoc(item)}`.toLowerCase().includes(text))
  }, [list, keyword])

  if (!open) return null

  return (
    <div className="modal-backdrop">
      <div className="modal" style={{ maxWidth: 680 }}>
        <div className="modal-header">
          <span className="modal-title">Lua API</span>
          <span className="text-3" style={{ fontSize: 12 }}>
            与后端注册表同源，改后端就跟着变
          </span>
          <button type="button" className="btn btn-ghost" onClick={() => setOpen(false)}>
            关闭
          </button>
        </div>

        <div className="modal-body" style={{ gap: 12 }}>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <div className="console-filters">
              {(Object.keys(TAB_LABEL) as Tab[]).map((item) => (
                <button
                  key={item}
                  type="button"
                  className={`console-filter ${tab === item ? 'console-filter-active' : ''}`}
                  onClick={() => setTab(item)}
                >
                  {TAB_LABEL[item]}
                </button>
              ))}
            </div>
            <span className="spacer" />
            <input
              className="input"
              style={{ maxWidth: 200 }}
              type="search"
              value={keyword}
              placeholder="过滤名称或说明"
              onChange={(event) => setKeyword(event.target.value)}
            />
          </div>

          {luaApi.isPending ? <div className="text-2">正在读取 /api/lua/api…</div> : null}

          {luaApi.isError ? (
            <div className="banner banner-danger" style={{ borderBottom: 'none' }}>
              <div className="banner-body">
                <div className="banner-title">Lua API 文档读取失败</div>
                <div>后端没有返回 /api/lua/api。编辑器补全与这里的列表都会是空的。</div>
                <button type="button" className="btn btn-secondary btn-sm" style={{ marginTop: 4 }} onClick={() => void luaApi.refetch()}>
                  重试
                </button>
              </div>
            </div>
          ) : null}

          {tab === 'functions' ? (
            filteredFunctions.length === 0 && !luaApi.isPending ? (
              <div className="text-2">没有匹配的函数。</div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8, maxHeight: 420, overflow: 'auto' }}>
                {filteredFunctions.map((item) => (
                  <div key={item.name} style={{ borderBottom: '1px solid var(--border-subtle)', paddingBottom: 8 }}>
                    <div className="mono" style={{ color: 'var(--syn-api)' }}>
                      {item.signature || item.name}
                    </div>
                    {item.doc ? (
                      <div className="text-2" style={{ fontSize: 12 }}>
                        {item.doc}
                      </div>
                    ) : null}
                  </div>
                ))}
              </div>
            )
          ) : filteredList.length === 0 && !luaApi.isPending ? (
            <div className="text-2">后端没有返回这一类的条目。</div>
          ) : (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, maxHeight: 420, overflow: 'auto' }}>
              {filteredList.map((item, index) => (
                <span className="kb-tag" key={`${luaItemName(item)}-${index}`} title={luaItemDoc(item) || luaItemSignature(item)}>
                  <span className="mono">{luaItemName(item)}</span>
                </span>
              ))}
            </div>
          )}
        </div>

        <div className="modal-footer">
          <span className="text-3" style={{ fontSize: 12 }}>
            补全只在编辑器里输入时触发；这里只做查询。
          </span>
          <span className="spacer" />
          <button type="button" className="btn btn-secondary" onClick={() => setOpen(false)}>
            知道了
          </button>
        </div>
      </div>
    </div>
  )
}
