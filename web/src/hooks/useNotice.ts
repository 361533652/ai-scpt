import { useCallback } from 'react'
import { describeError } from '../api/client'
import { useUiStore } from '../store/ui'

/** 统一的失败通知：标题写清是什么操作失败，正文给原因，附一行下一步。 */
export function useErrorNotice() {
  const notify = useUiStore((state) => state.notify)
  return useCallback(
    (title: string, error: unknown) => {
      const described = describeError(error)
      notify({
        tone: 'error',
        title,
        detail: described.reason,
        hint: `${described.nextStep}${described.traceId ? `（traceId ${described.traceId}）` : ''}`,
      })
    },
    [notify],
  )
}
