import { apiRequest } from './api.ts'
import type { Cart } from './types.ts'

const cartPath = (cartId: string) => `/carts/${encodeURIComponent(cartId)}`
const itemPath = (cartId: string, productId: string) => `${cartPath(cartId)}/items/${encodeURIComponent(productId)}`

export async function createCart(): Promise<Cart> {
  return (await apiRequest<Cart>('/carts', { method: 'POST' })).data
}

export async function getCart(cartId: string): Promise<Cart> {
  return (await apiRequest<Cart>(cartPath(cartId))).data
}

/** Adds to the quantity already in the cart (the server merges), so repeated clicks never lose an increment. */
export async function addCartItem(cartId: string, productId: string, quantity: number): Promise<Cart> {
  return (await apiRequest<Cart>(`${cartPath(cartId)}/items`, { method: 'POST', body: { productId, quantity } })).data
}

export async function setCartItemQuantity(cartId: string, productId: string, quantity: number): Promise<Cart> {
  return (await apiRequest<Cart>(itemPath(cartId, productId), { method: 'PUT', body: { quantity } })).data
}

export async function removeCartItem(cartId: string, productId: string): Promise<Cart> {
  return (await apiRequest<Cart>(itemPath(cartId, productId), { method: 'DELETE' })).data
}
