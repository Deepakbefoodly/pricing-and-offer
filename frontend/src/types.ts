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

export interface OrderLine {
  productId: string
  name: string
  /** Price at the moment of purchase; later catalogue changes never alter it. */
  unitPrice: Money
  quantity: number
  lineTotal: Money
}

export interface Order {
  id: string
  orderNumber: number
  cartId: string
  lines: OrderLine[]
  subtotal: Money
  coupon: { code: string; percentOff: number } | null
  discount: Money
  total: Money
  placedAt: string
}

export interface Coupon {
  code: string
  percentOff: number
  /** The placed-order count this coupon rewards (n, 2n, 3n, …). */
  milestoneOrderNumber: number
  status: 'AVAILABLE' | 'REDEEMED'
  generatedAt: string
  redeemedByOrderId: string | null
  redeemedAt: string | null
}

export interface SalesReport {
  totalOrders: number
  /** Only products that were actually sold. */
  quantityByProduct: { productId: string; name: string; quantity: number }[]
  grossRevenue: Money
  totalDiscounts: Money
  netRevenue: Money
  coupons: { generated: number; available: number; redeemed: number }
}

export interface Cart {
  id: string
  status: 'OPEN' | 'CHECKED_OUT'
  orderId: string | null
  items: CartItem[]
  itemCount: number
  subtotal: Money
}
