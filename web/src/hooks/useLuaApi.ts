import { useQuery } from '@tanstack/react-query'
import { api } from '../api/endpoints'
import type { LuaApiDoc, LuaApiItem } from '../api/types'

/** /api/lua/api 是编辑器补全、AI 提示词、帮助面板的共同来源，一次会话里不会变。 */
export function useLuaApi() {
  return useQuery({
    queryKey: ['lua-api'],
    queryFn: api.luaApi,
    staleTime: 30 * 60 * 1000,
  })
}

export function luaItemName(item: LuaApiItem): string {
  if (typeof item === 'string') return item
  return item.name ?? ''
}

export function luaItemDoc(item: LuaApiItem): string {
  if (typeof item === 'string') return ''
  return item.doc ?? item.signature ?? ''
}

export function luaItemSignature(item: LuaApiItem): string {
  if (typeof item === 'string') return ''
  return item.signature ?? ''
}

/** 补全用：把 functions / keys / events / mouseButtons 合成一组候选。 */
export interface CompletionItem {
  label: string
  type: 'function' | 'keyword'
  detail: string
}

export function buildCompletionItems(doc: LuaApiDoc | undefined): CompletionItem[] {
  if (!doc) return []
  const items: CompletionItem[] = []
  for (const fn of doc.functions ?? []) {
    items.push({ label: fn.name, type: 'function', detail: fn.signature || fn.doc || '' })
  }
  const pushKeyword = (list: LuaApiItem[] | undefined) => {
    for (const entry of list ?? []) {
      const label = luaItemName(entry)
      if (label) items.push({ label, type: 'keyword', detail: luaItemDoc(entry) })
    }
  }
  pushKeyword(doc.keys)
  pushKeyword(doc.events)
  pushKeyword(doc.mouseButtons)
  return items
}
