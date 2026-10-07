import { ApiError } from '../api.ts'
import ErrorMessage from '../components/ErrorMessage.tsx'
import StockBadge from '../components/StockBadge.tsx'
import { useCart } from '../context/CartContext.ts'
import { useToast } from '../context/ToastContext.ts'
import { useProducts } from '../hooks/useProducts.ts'
import type { Product } from '../types.ts'

export default function ProductsPage() {
  const { products, loading, error } = useProducts()
  const { addItem } = useCart()
  const toast = useToast()

  // Not disabled while another add is in flight: adds are merged server-side, so quick clicks are safe.
  async function add(product: Product) {
    try {
      const cart = await addItem(product.id, 1)
      const inCart = cart.items.find((item) => item.productId === product.id)?.quantity ?? 1
      toast.success(`Added ${product.name} (${inCart} in cart)`)
    } catch (e) {
      toast.error(e instanceof ApiError ? e : new ApiError('UNKNOWN', String(e), 0))
    }
  }

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
                  <button
                    type="button"
                    onClick={() => void add(product)}
                    disabled={product.availableQty === 0}
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
