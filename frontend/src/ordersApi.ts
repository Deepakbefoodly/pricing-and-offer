import { apiRequest } from './api.ts'
import type { Money, Order } from './types.ts'

/** `expectedSubtotal` is the subtotal shown to the customer; the server refuses to charge a different amount. */
export async function checkoutCart(cartId: string, expectedSubtotal: Money): Promise<Order> {
  return (
    await apiRequest<Order>(`/carts/${encodeURIComponent(cartId)}/checkout`, { method: 'POST', body: { expectedSubtotal } })
  ).data
}

export async function getOrder(orderId: string): Promise<Order> {
  return (await apiRequest<Order>(`/orders/${encodeURIComponent(orderId)}`)).data
}
