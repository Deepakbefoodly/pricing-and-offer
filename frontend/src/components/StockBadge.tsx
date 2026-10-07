const LOW_STOCK = 5

export default function StockBadge({ qty }: { qty: number }) {
  if (qty === 0) {
    return <span className="rounded bg-slate-200 px-2 py-0.5 text-xs text-slate-700">Sold out</span>
  }
  if (qty <= LOW_STOCK) {
    return <span className="rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-800">Only {qty} left</span>
  }
  return <span className="text-sm text-slate-600">{qty} in stock</span>
}
