import { apiRequest } from './api.ts'
import type { Money, Order } from './types.ts'

/** Everything needed to resend a checkout request exactly. */
export interface CheckoutRequest {
  cartId: string
  expectedSubtotal: Money
  idempotencyKey: string
}

export interface CheckoutResponse {
  order: Order
  /** True when the server recognised the key and returned the order it had already placed. */
  replayed: boolean
  status: number
}

/**
 * Places the order. `expectedSubtotal` is the subtotal shown to the customer; the server refuses to charge a
 * different amount. Resending with the same `idempotencyKey` never creates a second order.
 */
export async function checkoutCart({ cartId, expectedSubtotal, idempotencyKey }: CheckoutRequest): Promise<CheckoutResponse> {
  const response = await apiRequest<Order>(`/carts/${encodeURIComponent(cartId)}/checkout`, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: { expectedSubtotal },
  })
  return {
    order: response.data,
    replayed: response.headers.get('Idempotent-Replayed') === 'true',
    status: response.status,
  }
}

export async function getOrder(orderId: string): Promise<Order> {
  return (await apiRequest<Order>(`/orders/${encodeURIComponent(orderId)}`)).data
}
