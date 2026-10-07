import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { ApiError } from '../api.ts'
import ErrorMessage from '../components/ErrorMessage.tsx'
import RetryDemo from '../components/RetryDemo.tsx'
import { getOrder, type CheckoutRequest } from '../ordersApi.ts'
import type { Order } from '../types.ts'

export default function OrderPage() {
  const { orderId = '' } = useParams()
  // Present only when we arrived straight from checkout; enables the retry demo.
  const checkoutRequest = (useLocation().state as { checkoutRequest?: CheckoutRequest } | null)?.checkoutRequest
  const [result, setResult] = useState<{ orderId: string; order?: Order; error?: ApiError } | null>(null)

  useEffect(() => {
    let current = true
    getOrder(orderId)
      .then((order) => current && setResult({ orderId, order }))
      .catch((e: unknown) => current && setResult({ orderId, error: e instanceof ApiError ? e : new ApiError('UNKNOWN', String(e), 0) }))
    return () => {
      current = false
    }
  }, [orderId])

  // Ignore a result that belongs to a previously viewed order.
  if (result?.orderId !== orderId) {
    return <p className="text-sm text-slate-500">Loading order…</p>
  }
  if (result.error) {
    return <ErrorMessage error={result.error} />
  }
  const order = result.order!

  return (
    <section>
      <h1 className="text-xl font-semibold">Order #{order.orderNumber}</h1>
      <p className="mt-1 text-sm text-slate-600">
        Placed {new Date(order.placedAt).toLocaleString()} · <span className="font-mono text-xs">{order.id}</span>
      </p>

      <table className="mt-4 w-full overflow-hidden rounded border border-slate-200 bg-white text-left text-sm">
        <thead className="bg-slate-100 text-slate-600">
          <tr>
            <th className="px-3 py-2 font-medium">Product</th>
            <th className="px-3 py-2 text-right font-medium">Unit price</th>
            <th className="px-3 py-2 text-right font-medium">Qty</th>
            <th className="px-3 py-2 text-right font-medium">Line total</th>
          </tr>
        </thead>
        <tbody>
          {order.lines.map((line) => (
            <tr key={line.productId} className="border-t border-slate-100">
              <td className="px-3 py-2">{line.name}</td>
              <td className="px-3 py-2 text-right font-mono">{line.unitPrice}</td>
              <td className="px-3 py-2 text-right font-mono">{line.quantity}</td>
              <td className="px-3 py-2 text-right font-mono">{line.lineTotal}</td>
            </tr>
          ))}
        </tbody>
        <tfoot className="border-t border-slate-200">
          <tr>
            <td className="px-3 py-1.5" colSpan={3}>
              Subtotal
            </td>
            <td className="px-3 py-1.5 text-right font-mono">{order.subtotal}</td>
          </tr>
          <tr>
            <td className="px-3 py-1.5" colSpan={3}>
              Discount{order.coupon && ` (${order.coupon.code}, ${order.coupon.percentOff}% off)`}
            </td>
            <td className="px-3 py-1.5 text-right font-mono">{order.coupon ? `−${order.discount}` : order.discount}</td>
          </tr>
          <tr className="font-semibold">
            <td className="px-3 py-1.5" colSpan={3}>
              Total paid
            </td>
            <td className="px-3 py-1.5 text-right font-mono">{order.total}</td>
          </tr>
        </tfoot>
      </table>

      <Link to="/products" className="mt-4 inline-block text-sm text-blue-600 hover:underline">
        Continue shopping
      </Link>

      {checkoutRequest && <RetryDemo request={checkoutRequest} orderId={order.id} />}
    </section>
  )
}
