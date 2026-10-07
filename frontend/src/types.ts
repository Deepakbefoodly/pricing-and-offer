/** Money amounts are decimal strings ("25.00") exactly as the API sends them; never parse them into floats. */
export type Money = string

export interface Product {
  id: string
  name: string
  unitPrice: Money
  availableQty: number
  version: number
}
