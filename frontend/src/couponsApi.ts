import { apiRequest } from './api.ts'
import type { Coupon } from './types.ts'

/** Admin: generate the coupon for the oldest reached-but-unrewarded milestone (409 NO_ELIGIBLE_MILESTONE otherwise). */
export async function generateCoupon(): Promise<Coupon> {
  return (await apiRequest<Coupon>('/admin/coupons', { method: 'POST' })).data
}

export async function listCoupons(): Promise<Coupon[]> {
  return (await apiRequest<Coupon[]>('/admin/coupons')).data
}
