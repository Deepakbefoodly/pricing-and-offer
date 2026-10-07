import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../api.ts'
import { listProducts } from '../productsApi.ts'
import type { Product } from '../types.ts'

export function useProducts() {
  const [products, setProducts] = useState<Product[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<ApiError | null>(null)
  const [requestId, setRequestId] = useState(0)

  useEffect(() => {
    // Ignore responses that arrive after a newer request or an unmount.
    let current = true
    listProducts()
      .then((result) => {
        if (current) {
          setProducts(result)
          setError(null)
        }
      })
      .catch((e: unknown) => {
        if (current) {
          setError(toApiError(e))
        }
      })
      .finally(() => {
        if (current) {
          setLoading(false)
        }
      })
    return () => {
      current = false
    }
  }, [requestId])

  const reload = useCallback(() => {
    setLoading(true)
    setRequestId((id) => id + 1)
  }, [])

  return { products, setProducts, loading, error, reload }
}

export function toApiError(e: unknown): ApiError {
  return e instanceof ApiError ? e : new ApiError('UNKNOWN', String(e), 0)
}
