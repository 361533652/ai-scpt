/** 浏览器侧的文件下载与剪贴板，导出脚本/日志、复制代码块都用这两个。 */

export function downloadText(filename: string, text: string, mime = 'text/plain;charset=utf-8'): void {
  const blob = new Blob([text], { type: mime })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 2000)
}

export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 非安全上下文里 clipboard API 会被拒，走下面的兜底。
  }
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', 'readonly')
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    textarea.remove()
    return ok
  } catch {
    return false
  }
}

export function pickLuaFile(): Promise<{ filename: string; content: string } | null> {
  return new Promise((resolve) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.lua,text/plain'
    input.style.display = 'none'
    document.body.appendChild(input)
    let settled = false
    const finish = (value: { filename: string; content: string } | null) => {
      if (settled) return
      settled = true
      input.remove()
      resolve(value)
    }
    input.addEventListener('change', () => {
      const file = input.files?.[0]
      if (!file) {
        finish(null)
        return
      }
      const reader = new FileReader()
      reader.onload = () => finish({ filename: file.name, content: String(reader.result ?? '') })
      reader.onerror = () => finish(null)
      reader.readAsText(file, 'utf-8')
    })
    input.addEventListener('cancel', () => finish(null))
    input.click()
  })
}
