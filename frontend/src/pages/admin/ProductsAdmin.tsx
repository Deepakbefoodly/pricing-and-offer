import { useState } from 'react'
import { ApiError } from '../../api.ts'
import ErrorMessage from '../../components/ErrorMessage.tsx'
import { toApiError, useProducts } from '../../hooks/useProducts.ts'
import { updateProduct } from '../../productsApi.ts'
import type { Product } from '../../types.ts'

/** Admin → Products: edit price and stock. Each save carries the version the row was loaded with. */
export default function ProductsAdmin() {
  const { products, setProducts, loading, error, reload } = useProducts()
  // Lives here, not in the row: a stale-version reload remounts the row and would drop its state.
  const [conflict, setConflict] = useState<{ product: string; error: ApiError } | null>(null)

  const handleSaved = (updated: Product) => {
    setConflict(null)
    setProducts((current) => current.map((p) => (p.id === updated.id ? updated : p)))
  }

  const handleStale = (product: Product, staleError: ApiError) => {
    setConflict({ product: product.name, error: staleError })
    reload()
  }

  return (
    <section>
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Products</h2>
        <button type="button" onClick={reload} className="text-sm text-blue-600 hover:underline">
          Reload
        </button>
      </div>
      {error && (
        <div className="mt-3">
          <ErrorMessage error={error} />
        </div>
      )}
      {conflict && (
        <div className="mt-3">
          <ErrorMessage error={conflict.error} />
          <p className="mt-1 text-xs text-slate-600">
            {conflict.product} was changed elsewhere, so your edit was not saved. The latest values are loaded below; re-apply
            your change if it is still needed.
          </p>
        </div>
      )}
      {loading && products.length === 0 ? (
        <p className="mt-3 text-sm text-slate-500">Loading…</p>
      ) : (
        <div className="mt-3 divide-y divide-slate-100 rounded border border-slate-200 bg-white">
          {products.map((product) => (
            // Keyed by version so the inputs reset to server values after every save or reload.
            <ProductRow key={`${product.id}@${product.version}`} product={product} onSaved={handleSaved} onStale={handleStale} />
          ))}
        </div>
      )}
    </section>
  )
}

interface RowProps {
  product: Product
  onSaved: (product: Product) => void
  onStale: (product: Product, error: ApiError) => void
}

function ProductRow({ product, onSaved, onStale }: RowProps) {
  const [price, setPrice] = useState(product.unitPrice)
  const [stock, setStock] = useState(String(product.availableQty))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  const priceChanged = price !== product.unitPrice
  const stockChanged = stock !== String(product.availableQty)

  async function save() {
    // Number('') is 0, so check the text first rather than sending a silently converted value.
    if (stockChanged && !/^\d+$/.test(stock.trim())) {
      setError(new ApiError('VALIDATION_ERROR', 'Stock must be a whole number', 0, { fields: { availableQty: 'whole number ≥ 0' } }))
      return
    }
    setSaving(true)
    setError(null)
    try {
      const updated = await updateProduct(product.id, {
        version: product.version,
        ...(priceChanged && { unitPrice: price.trim() }),
        ...(stockChanged && { availableQty: Number(stock.trim()) }),
      })
      onSaved(updated)
    } catch (e) {
      const apiError = toApiError(e)
      if (apiError.code === 'PRODUCT_MODIFIED') {
        onStale(product, apiError)
      } else {
        setError(apiError)
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="px-3 py-3">
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-40 flex-1">
          <div className="font-medium">{product.name}</div>
          <div className="font-mono text-xs text-slate-500">
            {product.id} · v{product.version}
          </div>
        </div>
        <label className="text-xs text-slate-600">
          Price
          <input
            value={price}
            onChange={(e) => setPrice(e.target.value)}
            inputMode="decimal"
            className="mt-1 block w-28 rounded border border-slate-300 px-2 py-1 font-mono text-sm"
          />
        </label>
        <label className="text-xs text-slate-600">
          Stock
          <input
            value={stock}
            onChange={(e) => setStock(e.target.value)}
            type="number"
            min={0}
            step={1}
            className="mt-1 block w-24 rounded border border-slate-300 px-2 py-1 font-mono text-sm"
          />
        </label>
        <button
          type="button"
          onClick={() => void save()}
          disabled={saving || (!priceChanged && !stockChanged)}
          className="rounded bg-slate-900 px-3 py-1.5 text-sm text-white disabled:opacity-40"
        >
          {saving ? 'Saving…' : 'Save'}
        </button>
      </div>
      {error && (
        <div className="mt-2">
          <ErrorMessage error={error} />
        </div>
      )}
    </div>
  )
}
