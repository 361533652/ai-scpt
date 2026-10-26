import { useState, type CSSProperties, type PointerEvent as ReactPointerEvent } from 'react'

export type SplitterOrientation = 'vertical' | 'horizontal'

interface SplitterProps {
  orientation: SplitterOrientation
  /** 增量像素：向右/向下为正。 */
  onResize: (delta: number) => void
  className?: string
  title?: string
}

/** 面板拖拽条。只在拖动期间接管指针，不用全局监听。 */
export function Splitter({ orientation, onResize, className, title }: SplitterProps) {
  const [dragging, setDragging] = useState(false)
  const [last, setLast] = useState(0)

  const handlePointerDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault()
    event.currentTarget.setPointerCapture(event.pointerId)
    setDragging(true)
    setLast(orientation === 'vertical' ? event.clientX : event.clientY)
  }

  const handlePointerMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!dragging) return
    const current = orientation === 'vertical' ? event.clientX : event.clientY
    const delta = current - last
    if (delta === 0) return
    setLast(current)
    onResize(delta)
  }

  const finish = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (!dragging) return
    setDragging(false)
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId)
    }
  }

  const style: CSSProperties = orientation === 'vertical' ? { width: 5 } : { height: 5 }

  return (
    <div
      className={[
        'splitter',
        orientation === 'vertical' ? 'splitter-v' : 'splitter-h',
        dragging ? 'splitter-active' : '',
        className ?? '',
      ]
        .filter(Boolean)
        .join(' ')}
      style={style}
      role="separator"
      aria-orientation={orientation}
      title={title}
      onPointerDown={handlePointerDown}
      onPointerMove={handlePointerMove}
      onPointerUp={finish}
      onPointerCancel={finish}
    />
  )
}
