/** Money amounts are decimal strings ("25.00") exactly as the API sends them; never parse them into floats. */
export type Money = string

export interface Product {
  id: string
  name: string
  unitPrice: Money
  availableQty: number
  version: number
}

export interface CartItem {
  productId: string
  name: string
  /** Current catalogue price; the cart never stores prices. */
  unitPrice: Money
  quantity: number
  lineTotal: Money
  availableQty: number
  /** False when stock dropped below the cart quantity; checkout would be rejected. */
  inStock: boolean
}

export interface Cart {
  id: string
  status: 'OPEN' | 'CHECKED_OUT'
  orderId: string | null
  items: CartItem[]
  itemCount: number
  subtotal: Money
}
