import { createContext, useContext } from 'react'
import type { ApiError } from '../api.ts'

export interface ToastApi {
  success: (message: string) => void
  error: (error: ApiError) => void
}

export const ToastContext = createContext<ToastApi | null>(null)

export function useToast(): ToastApi {
  const toast = useContext(ToastContext)
  if (!toast) {
    throw new Error('useToast must be used inside <ToastProvider>')
  }
  return toast
}
