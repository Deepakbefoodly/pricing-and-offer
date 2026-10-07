import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '../api.ts'
import { addCartItem, createCart, getCart, removeCartItem, setCartItemQuantity } from '../cartApi.ts'
import { CartContext, type CartApi } from '../context/CartContext.ts'
import type { Cart } from '../types.ts'

const STORAGE_KEY = 'cartId'

function readStoredCartId(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

function storeCartId(cartId: string | null) {
  try {
    if (cartId) {
      localStorage.setItem(STORAGE_KEY, cartId)
    } else {
      localStorage.removeItem(STORAGE_KEY)
    }
  } catch {
    // Storage unavailable (private mode, blocked): the cart still works for this page session.
  }
}

const isMissingCart = (e: unknown) => e instanceof ApiError && e.code === 'CART_NOT_FOUND'

/**
 * Holds the shopper's cart for every page. The cart ID survives reloads via localStorage;
 * the backend is in-memory, so a stored ID can go stale after a restart and is then dropped.
 */
export default function CartProvider({ children }: { children: ReactNode }) {
  const [cart, setCart] = useState<Cart | null>(null)
  const [loading, setLoading] = useState(() => readStoredCartId() !== null)
  const [inFlight, setInFlight] = useState(0) // a counter, so overlapping requests don't clear "busy" early
  const [error, setError] = useState<ApiError | null>(null)
  const cartIdRef = useRef<string | null>(readStoredCartId())
  // Shared so two quick "Add to cart" clicks on a fresh session create one cart, not two.
  const creatingRef = useRef<Promise<string> | null>(null)

  const adopt = useCallback((next: Cart | null) => {
    cartIdRef.current = next?.id ?? null
    storeCartId(cartIdRef.current)
    setCart(next)
  }, [])

  const load = useCallback(async () => {
    const cartId = cartIdRef.current
    if (!cartId) {
      return
    }
    try {
      adopt(await getCart(cartId))
      setError(null)
    } catch (e) {
      if (isMissingCart(e)) {
        adopt(null)
      } else {
        setError(e instanceof ApiError ? e : new ApiError('UNKNOWN', String(e), 0))
      }
    }
  }, [adopt])

  useEffect(() => {
    // State is set only after the request settles.
    void load().finally(() => setLoading(false))
  }, [load])

  const ensureCartId = useCallback(async () => {
    if (cartIdRef.current) {
      return cartIdRef.current
    }
    creatingRef.current ??= createCart()
      .then((created) => {
        adopt(created)
        return created.id
      })
      .finally(() => {
        creatingRef.current = null
      })
    return creatingRef.current
  }, [adopt])

  /** Runs a cart request, keeps the context in sync, and clears a cart the backend no longer knows. */
  const run = useCallback(
    async (request: (cartId: string) => Promise<Cart>, createIfMissing: boolean): Promise<Cart> => {
      setInFlight((n) => n + 1)
      try {
        const cartId = createIfMissing ? await ensureCartId() : cartIdRef.current
        if (!cartId) {
          throw new ApiError('CART_NOT_FOUND', 'There is no cart yet', 404)
        }
        try {
          const updated = await request(cartId)
          adopt(updated)
          return updated
        } catch (e) {
          if (!isMissingCart(e)) {
            throw e
          }
          adopt(null)
          if (!createIfMissing) {
            throw e
          }
          // The stored cart vanished (backend restart): start a new one and retry once.
          const updated = await request(await ensureCartId())
          adopt(updated)
          return updated
        }
      } finally {
        setInFlight((n) => n - 1)
      }
    },
    [adopt, ensureCartId],
  )

  const api = useMemo<CartApi>(
    () => ({
      cart,
      loading,
      busy: inFlight > 0,
      error,
      addItem: (productId, quantity) => run((id) => addCartItem(id, productId, quantity), true),
      setQuantity: (productId, quantity) => run((id) => setCartItemQuantity(id, productId, quantity), false),
      removeItem: (productId) => run((id) => removeCartItem(id, productId), false),
      refresh: load,
    }),
    [cart, loading, inFlight, error, run, load],
  )

  return <CartContext.Provider value={api}>{children}</CartContext.Provider>
}
