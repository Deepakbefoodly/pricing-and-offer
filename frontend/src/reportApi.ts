import { apiRequest } from './api.ts'
import type { Order, SalesReport } from './types.ts'

/** Admin: read-only sales summary computed from orders and coupons. */
export async function getReport(): Promise<SalesReport> {
  return (await apiRequest<SalesReport>('/admin/report')).data
}

/** Admin: every placed order, oldest first, for reconciling the report. */
export async function listOrders(): Promise<Order[]> {
  return (await apiRequest<Order[]>('/admin/orders')).data
}
