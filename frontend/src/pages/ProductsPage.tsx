import ErrorMessage from '../components/ErrorMessage.tsx'
import StockBadge from '../components/StockBadge.tsx'
import { useProducts } from '../hooks/useProducts.ts'

export default function ProductsPage() {
  const { products, loading, error } = useProducts()

  return (
    <section>
      <h1 className="text-xl font-semibold">Products</h1>
      {error && (
        <div className="mt-4">
          <ErrorMessage error={error} />
        </div>
      )}
      {loading && products.length === 0 ? (
        <p className="mt-4 text-sm text-slate-500">Loading…</p>
      ) : (
        <table className="mt-4 w-full overflow-hidden rounded border border-slate-200 bg-white text-left text-sm">
          <thead className="bg-slate-100 text-slate-600">
            <tr>
              <th className="px-3 py-2 font-medium">Product</th>
              <th className="px-3 py-2 text-right font-medium">Price</th>
              <th className="px-3 py-2 font-medium">Availability</th>
              <th className="px-3 py-2" />
            </tr>
          </thead>
          <tbody>
            {products.map((product) => (
              <tr key={product.id} className="border-t border-slate-100">
                <td className="px-3 py-2">{product.name}</td>
                <td className="px-3 py-2 text-right font-mono">{product.unitPrice}</td>
                <td className="px-3 py-2">
                  <StockBadge qty={product.availableQty} />
                </td>
                <td className="px-3 py-2 text-right">
                  {/* Wired up when carts arrive (Slice 2). */}
                  <button
                    type="button"
                    disabled
                    title="Carts arrive in Slice 2"
                    className="rounded bg-slate-900 px-3 py-1 text-xs text-white disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    Add to cart
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
