import { useCallback, useMemo, useRef, useState, type ReactNode } from 'react'
import { ToastContext, type ToastApi } from '../context/ToastContext.ts'

interface Toast {
  id: number
  kind: 'success' | 'error'
  code?: string
  message: string
}

const DISMISS_AFTER_MS = 4000

export default function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([])
  const nextId = useRef(0)

  const push = useCallback((toast: Omit<Toast, 'id'>) => {
    const id = nextId.current++
    setToasts((current) => [...current, { ...toast, id }])
    setTimeout(() => setToasts((current) => current.filter((t) => t.id !== id)), DISMISS_AFTER_MS)
  }, [])

  const api = useMemo<ToastApi>(
    () => ({
      success: (message) => push({ kind: 'success', message }),
      error: (error) => push({ kind: 'error', code: error.code, message: error.message }),
    }),
    [push],
  )

  return (
    <ToastContext.Provider value={api}>
      {children}
      <div aria-live="polite" className="fixed right-4 bottom-4 z-50 flex w-80 flex-col gap-2">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            role={toast.kind === 'error' ? 'alert' : 'status'}
            className={`rounded border px-3 py-2 text-sm shadow ${
              toast.kind === 'error' ? 'border-red-200 bg-red-50 text-red-800' : 'border-emerald-200 bg-emerald-50 text-emerald-800'
            }`}
          >
            {toast.code && <span className="mr-1 font-mono text-xs font-semibold">{toast.code}</span>}
            {toast.message}
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  )
}
