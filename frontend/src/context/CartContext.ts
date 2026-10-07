import { createContext, useContext } from 'react'
import type { ApiError } from '../api.ts'
import type { Cart } from '../types.ts'

export interface CartApi {
  /** Null until the first item is added (or after the stored cart disappeared, e.g. a backend restart). */
  cart: Cart | null
  loading: boolean
  /** True while a cart request is in flight; screens disable cart controls meanwhile. */
  busy: boolean
  error: ApiError | null
  /** Creates the cart on first use. Rejects with ApiError. */
  addItem: (productId: string, quantity: number) => Promise<Cart>
  setQuantity: (productId: string, quantity: number) => Promise<Cart>
  removeItem: (productId: string) => Promise<Cart>
  /** Re-reads the cart so prices and stock reflect the current catalogue. */
  refresh: () => Promise<void>
}

export const CartContext = createContext<CartApi | null>(null)

export function useCart(): CartApi {
  const cart = useContext(CartContext)
  if (!cart) {
    throw new Error('useCart must be used inside <CartProvider>')
  }
  return cart
}
