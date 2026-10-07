import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '../api.ts'
import { addCartItem, createCart, getCart, removeCartItem, setCartItemQuantity } from '../cartApi.ts'
import { CartContext, type CartApi } from '../context/CartContext.ts'
import { checkoutCart } from '../ordersApi.ts'
import type { Cart, Order } from '../types.ts'

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

/**
 * The stored cart can no longer be shopped with: the backend forgot it (in-memory restart) or it was
 * already checked out (e.g. in another tab). Either way the next "Add to cart" should start a new cart.
 */
const isUnusableCart = (e: unknown) => e instanceof ApiError && (e.code === 'CART_NOT_FOUND' || e.code === 'CART_NOT_OPEN')

/** Checkout failures that mean the cart on screen is out of date; reload it so the customer sees why. */
const isStaleView = (e: unknown) => e instanceof ApiError && (e.code === 'PRICE_CHANGED' || e.code === 'INSUFFICIENT_STOCK')

/**
 * Holds the shopper's cart for every page. The cart ID survives reloads via localStorage and is dropped
 * once it stops being usable (unknown to the backend, or checked out).
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
      const loaded = await getCart(cartId)
      adopt(loaded.status === 'OPEN' ? loaded : null)
      setError(null)
    } catch (e) {
      if (isUnusableCart(e)) {
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
          if (!isUnusableCart(e)) {
            throw e
          }
          adopt(null)
          if (!createIfMissing) {
            throw e
          }
          // The stored cart vanished or was checked out elsewhere: start a new one and retry once.
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

  const checkout = useCallback(
    async (expectedSubtotal: string): Promise<Order> => {
      const cartId = cartIdRef.current
      if (!cartId) {
        throw new ApiError('CART_NOT_FOUND', 'There is no cart to check out', 404)
      }
      setInFlight((n) => n + 1)
      try {
        const order = await checkoutCart(cartId, expectedSubtotal)
        adopt(null) // the cart is closed; the next "Add to cart" starts a new one
        return order
      } catch (e) {
        if (isUnusableCart(e)) {
          adopt(null)
        } else if (isStaleView(e)) {
          await load()
        }
        throw e
      } finally {
        setInFlight((n) => n - 1)
      }
    },
    [adopt, load],
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
      checkout,
    }),
    [cart, loading, inFlight, error, run, load, checkout],
  )

  return <CartContext.Provider value={api}>{children}</CartContext.Provider>
}
