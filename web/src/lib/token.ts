/**
 * token 的读写与引导。
 * 契约：所有 /api/** 都要 token，优先请求头 X-GmScript-Token。
 * 页面从 URL 查询参数 token 拿一次，之后放 sessionStorage（关掉页面即失效，符合本地工具语义）。
 */

const TOKEN_KEY = 'gmscpt.token'

export const TOKEN_HEADER = 'X-GmScript-Token'

export type TokenOrigin = 'url' | 'storage' | 'none'

export interface TokenBootstrap {
  token: string
  origin: TokenOrigin
}

export function bootstrapToken(): TokenBootstrap {
  let fromUrl: string | null = null
  try {
    fromUrl = new URLSearchParams(window.location.search).get('token')
  } catch {
    fromUrl = null
  }
  const trimmed = fromUrl?.trim()
  if (trimmed) {
    sessionStorage.setItem(TOKEN_KEY, trimmed)
    return { token: trimmed, origin: 'url' }
  }
  const stored = sessionStorage.getItem(TOKEN_KEY)
  if (stored) {
    return { token: stored, origin: 'storage' }
  }
  return { token: '', origin: 'none' }
}

export function getToken(): string {
  try {
    return sessionStorage.getItem(TOKEN_KEY) ?? ''
  } catch {
    return ''
  }
}

export function setToken(token: string): void {
  sessionStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  sessionStorage.removeItem(TOKEN_KEY)
}
