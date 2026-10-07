import { apiRequest } from './api.ts'
import type { Money, Product } from './types.ts'

export async function listProducts(): Promise<Product[]> {
  return (await apiRequest<Product[]>('/products')).data
}

export interface ProductUpdate {
  /** The version the admin loaded; the backend rejects the edit if the product changed since. */
  version: number
  unitPrice?: Money
  availableQty?: number
}

export async function updateProduct(id: string, update: ProductUpdate): Promise<Product> {
  return (await apiRequest<Product>(`/admin/products/${encodeURIComponent(id)}`, { method: 'PATCH', body: update })).data
}
