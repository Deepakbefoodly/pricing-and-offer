import { createContext, useContext } from 'react'
import type { ApiError } from '../api.ts'
import type { CheckoutRequest } from '../ordersApi.ts'
import type { Cart, Money, Order } from '../types.ts'

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
  /**
   * Places the order for the subtotal the customer saw. On success the cart is cleared. On PRICE_CHANGED or
   * INSUFFICIENT_STOCK the cart is reloaded (so the screen shows the new prices / stock) and the error rethrown.
   */
  checkout: (expectedSubtotal: Money, couponCode?: string) => Promise<CheckoutOutcome>
}

export interface CheckoutOutcome {
  order: Order
  replayed: boolean
  /** The exact request that placed the order, so it can be resent to demonstrate idempotency. */
  request: CheckoutRequest
}

export const CartContext = createContext<CartApi | null>(null)

export function useCart(): CartApi {
  const cart = useContext(CartContext)
  if (!cart) {
    throw new Error('useCart must be used inside <CartProvider>')
  }
  return cart
}
