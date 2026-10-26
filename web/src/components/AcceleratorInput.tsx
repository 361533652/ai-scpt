import { useEffect, useRef, useState } from 'react'
import { describeAccelerator, isCapturableMouseButton, normalizeKeyEvent, normalizeMouseEvent } from '../lib/keys'

interface AcceleratorInputProps {
  value: string
  onChange: (accelerator: string) => void
  placeholder?: string
  /** 录制中的提示，例如「按下要用于急停的组合」。 */
  hint?: string
}

/**
 * 组合键录制控件。点击后进入录制，按下键盘或鼠标键即写入规范形式（ctrl+shift+f8 / mouse4）。
 * Esc 或右键取消录制。
 */
export function AcceleratorInput({ value, onChange, placeholder = '录制', hint }: AcceleratorInputProps) {
  const [capturing, setCapturing] = useState(false)
  const valueRef = useRef(value)
  valueRef.current = value

  useEffect(() => {
    if (!capturing) return

    const stop = (event: Event) => {
      event.preventDefault()
      event.stopPropagation()
    }

    const onKeyDown = (event: KeyboardEvent) => {
      stop(event)
      if (event.key === 'Escape') {
        setCapturing(false)
        return
      }
      const accelerator = normalizeKeyEvent(event)
      if (accelerator) onChange(accelerator)
      setCapturing(false)
    }

    const onKeyUp = (event: KeyboardEvent) => stop(event)

    const onMouseDown = (event: MouseEvent) => {
      stop(event)
      if (event.button === 2) {
        setCapturing(false)
        return
      }
      if (isCapturableMouseButton(event.button)) onChange(normalizeMouseEvent(event))
      setCapturing(false)
    }

    const onContextMenu = (event: MouseEvent) => stop(event)

    window.addEventListener('keydown', onKeyDown, true)
    window.addEventListener('keyup', onKeyUp, true)
    window.addEventListener('mousedown', onMouseDown, true)
    window.addEventListener('contextmenu', onContextMenu, true)
    return () => {
      window.removeEventListener('keydown', onKeyDown, true)
      window.removeEventListener('keyup', onKeyUp, true)
      window.removeEventListener('mousedown', onMouseDown, true)
      window.removeEventListener('contextmenu', onContextMenu, true)
    }
  }, [capturing, onChange])

  return (
    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
      <button
        type="button"
        className={`hotkey-capture ${capturing ? 'hotkey-capture-active' : ''} ${value ? '' : 'hotkey-capture-empty'}`}
        style={{ flex: '1 1 auto', maxWidth: 240 }}
        onClick={() => setCapturing((current) => !current)}
      >
        <span>{capturing ? '按下组合键…' : value ? describeAccelerator(value) : placeholder}</span>
        <span className="mono text-3">{value || '—'}</span>
      </button>
      {value ? (
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange('')}>
          清除
        </button>
      ) : null}
      <span className="field-hint">{capturing ? (hint ?? 'Esc 取消') : ''}</span>
    </div>
  )
}
