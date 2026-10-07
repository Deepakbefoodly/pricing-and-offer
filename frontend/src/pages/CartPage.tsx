import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { ApiError } from '../api.ts'
import ErrorMessage from '../components/ErrorMessage.tsx'
import { useCart } from '../context/CartContext.ts'
import { useToast } from '../context/ToastContext.ts'
import type { CartItem } from '../types.ts'

const toApiError = (e: unknown) => (e instanceof ApiError ? e : new ApiError('UNKNOWN', String(e), 0))

export default function CartPage() {
  const { cart, loading, busy, error, setQuantity, removeItem, addItem, refresh, checkout } = useCart()
  const toast = useToast()
  const navigate = useNavigate()
  // Why the last checkout was refused when the customer has to act on it (new prices, too little stock).
  const [checkoutProblem, setCheckoutProblem] = useState<ApiError | null>(null)
  const [couponCode, setCouponCode] = useState('')
  const [couponProblem, setCouponProblem] = useState<ApiError | null>(null)

  const attempt = async (action: () => Promise<unknown>) => {
    setCheckoutProblem(null)
    try {
      await action()
    } catch (e) {
      toast.error(toApiError(e))
    }
  }

  async function placeOrder(expectedSubtotal: string) {
    setCheckoutProblem(null)
    setCouponProblem(null)
    try {
      const { order, replayed, request } = await checkout(expectedSubtotal, couponCode.trim() || undefined)
      // A replay here means a lost response was retried automatically; to the shopper it is simply placed.
      toast.success(`Order #${order.orderNumber} placed${replayed ? ' (confirmed after a retry)' : ''}`)
      // Pass the request along so the order page can demonstrate a safe retry.
      navigate(`/orders/${order.id}`, { state: { checkoutRequest: request } })
    } catch (e) {
      const apiError = toApiError(e)
      if (apiError.code === 'PRICE_CHANGED' || apiError.code === 'INSUFFICIENT_STOCK') {
        setCheckoutProblem(apiError) // the cart has been reloaded with current prices and stock
      } else if (apiError.code === 'COUPON_NOT_FOUND' || apiError.code === 'COUPON_ALREADY_REDEEMED') {
        setCouponProblem(apiError) // nothing was charged; fix or clear the code and try again
      } else if (apiError.code === 'CART_ALREADY_CHECKED_OUT' && typeof apiError.details.orderId === 'string') {
        toast.error(apiError) // e.g. checked out from another tab: show the order it became
        navigate(`/orders/${apiError.details.orderId}`)
      } else {
        toast.error(apiError)
      }
    }
  }

  if (loading) {
    return <p className="text-sm text-slate-500">Loading cart…</p>
  }

  const hasItems = cart !== null && cart.items.length > 0
  const outOfStock = cart?.items.filter((item) => !item.inStock) ?? []

  return (
    <section>
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Cart</h1>
        {cart && (
          <button type="button" onClick={() => void refresh()} className="text-sm text-blue-600 hover:underline">
            Refresh prices &amp; stock
          </button>
        )}
      </div>

      {error && (
        <div className="mt-4">
          <ErrorMessage error={error} />
        </div>
      )}

      {!hasItems ? (
        <p className="mt-4 text-sm text-slate-600">
          Your cart is empty.{' '}
          <Link to="/products" className="text-blue-600 hover:underline">
            Browse products
          </Link>
        </p>
      ) : (
        <>
          <p className="mt-1 font-mono text-xs text-slate-500">{cart.id}</p>
          <table className="mt-4 w-full overflow-hidden rounded border border-slate-200 bg-white text-left text-sm">
            <thead className="bg-slate-100 text-slate-600">
              <tr>
                <th className="px-3 py-2 font-medium">Product</th>
                <th className="px-3 py-2 text-right font-medium">Unit price</th>
                <th className="px-3 py-2 text-center font-medium">Quantity</th>
                <th className="px-3 py-2 text-right font-medium">Line total</th>
                <th className="px-3 py-2" />
              </tr>
            </thead>
            <tbody>
              {cart.items.map((item) => (
                <CartRow
                  key={item.productId}
                  item={item}
                  disabled={busy}
                  onDecrease={() => attempt(() => setQuantity(item.productId, item.quantity - 1))}
                  onIncrease={() => attempt(() => addItem(item.productId, 1))}
                  onRemove={() => attempt(() => removeItem(item.productId))}
                />
              ))}
            </tbody>
            <tfoot>
              <tr className="border-t border-slate-200 font-medium">
                <td className="px-3 py-2" colSpan={3}>
                  Subtotal ({cart.itemCount} {cart.itemCount === 1 ? 'item' : 'items'})
                </td>
                <td className="px-3 py-2 text-right font-mono">{cart.subtotal}</td>
                <td />
              </tr>
            </tfoot>
          </table>

          {outOfStock.length > 0 && (
            <p className="mt-3 rounded border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">
              Some items no longer have enough stock. Reduce or remove them before checking out.
            </p>
          )}

          {checkoutProblem?.code === 'PRICE_CHANGED' && (
            <div role="alert" className="mt-3 rounded border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-900">
              Prices changed since you opened the cart: the subtotal was{' '}
              <span className="font-mono">{String(checkoutProblem.details.expectedSubtotal)}</span> and is now{' '}
              <span className="font-mono">{String(checkoutProblem.details.currentSubtotal)}</span>. You have not been charged.
              Review the cart above and press Checkout again to pay the new total.
            </div>
          )}
          {checkoutProblem?.code === 'INSUFFICIENT_STOCK' && (
            <div className="mt-3">
              <ErrorMessage error={checkoutProblem} />
            </div>
          )}

          <div className="mt-4 flex flex-wrap items-start justify-end gap-3">
            <label className="text-sm text-slate-600">
              <span className="sr-only">Coupon code</span>
              <input
                value={couponCode}
                onChange={(e) => {
                  setCouponCode(e.target.value)
                  setCouponProblem(null)
                }}
                placeholder="Coupon code (optional)"
                autoComplete="off"
                className="w-56 rounded border border-slate-300 px-2 py-1.5 font-mono text-sm uppercase placeholder:normal-case placeholder:font-sans"
              />
              {couponProblem && <span className="mt-1 block max-w-56 text-xs text-red-700">{couponProblem.message}</span>}
            </label>
            <span className="py-1.5 text-sm text-slate-600">
              {couponCode.trim() ? 'Subtotal before coupon ' : 'You pay '}
              <span className="font-mono font-medium text-slate-900">{cart.subtotal}</span>
            </span>
            <button
              type="button"
              onClick={() => void placeOrder(cart.subtotal)}
              disabled={busy || outOfStock.length > 0}
              className="rounded bg-slate-900 px-4 py-2 text-sm text-white disabled:cursor-not-allowed disabled:opacity-40"
            >
              {busy ? 'Working…' : 'Checkout'}
            </button>
          </div>
        </>
      )}
    </section>
  )
}

interface RowProps {
  item: CartItem
  disabled: boolean
  onDecrease: () => void
  onIncrease: () => void
  onRemove: () => void
}

function CartRow({ item, disabled, onDecrease, onIncrease, onRemove }: RowProps) {
  const stepButton = 'h-7 w-7 rounded border border-slate-300 bg-white text-slate-700 hover:bg-slate-50 disabled:opacity-40'
  return (
    <tr className={`border-t border-slate-100 ${item.inStock ? '' : 'bg-amber-50'}`}>
      <td className="px-3 py-2">
        <div>{item.name}</div>
        {!item.inStock && (
          <div className="text-xs text-amber-800">
            {item.availableQty === 0 ? 'Sold out' : `Only ${item.availableQty} in stock`}
          </div>
        )}
      </td>
      <td className="px-3 py-2 text-right font-mono">{item.unitPrice}</td>
      <td className="px-3 py-2">
        <div className="flex items-center justify-center gap-2">
          <button
            type="button"
            aria-label={`Decrease ${item.name}`}
            onClick={onDecrease}
            disabled={disabled || item.quantity <= 1}
            className={stepButton}
          >
            −
          </button>
          <span className="w-6 text-center font-mono">{item.quantity}</span>
          <button
            type="button"
            aria-label={`Increase ${item.name}`}
            onClick={onIncrease}
            disabled={disabled || item.quantity >= item.availableQty}
            className={stepButton}
          >
            +
          </button>
        </div>
      </td>
      <td className="px-3 py-2 text-right font-mono">{item.lineTotal}</td>
      <td className="px-3 py-2 text-right">
        <button type="button" onClick={onRemove} disabled={disabled} className="text-xs text-red-700 hover:underline disabled:opacity-40">
          Remove
        </button>
      </td>
    </tr>
  )
}
