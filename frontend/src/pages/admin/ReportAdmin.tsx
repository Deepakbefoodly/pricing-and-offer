import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../api.ts'
import ErrorMessage from '../../components/ErrorMessage.tsx'
import { getReport, listOrders } from '../../reportApi.ts'
import type { Order, SalesReport } from '../../types.ts'

/** Admin → Report: the sales summary, with the orders it is computed from listed underneath for reconciliation. */
export default function ReportAdmin() {
  const [data, setData] = useState<{ report: SalesReport; orders: Order[] } | null>(null)
  const [error, setError] = useState<ApiError | null>(null)
  const [requestId, setRequestId] = useState(0)

  useEffect(() => {
    let current = true
    Promise.all([getReport(), listOrders()])
      .then(([report, orders]) => {
        if (current) {
          setData({ report, orders })
          setError(null)
        }
      })
      .catch((e: unknown) => {
        if (current) {
          setError(e instanceof ApiError ? e : new ApiError('UNKNOWN', String(e), 0))
        }
      })
    return () => {
      current = false
    }
  }, [requestId])

  return (
    <section>
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Report</h2>
        <button type="button" onClick={() => setRequestId((id) => id + 1)} className="text-sm text-blue-600 hover:underline">
          Refresh
        </button>
      </div>
      {error && (
        <div className="mt-3">
          <ErrorMessage error={error} />
        </div>
      )}
      {data && <ReportBody report={data.report} orders={data.orders} />}
    </section>
  )
}

function ReportBody({ report, orders }: { report: SalesReport; orders: Order[] }) {
  const cards = [
    { label: 'Orders placed', value: String(report.totalOrders) },
    { label: 'Gross revenue', value: report.grossRevenue, mono: true },
    { label: 'Discounts granted', value: report.totalDiscounts, mono: true },
    { label: 'Net revenue', value: report.netRevenue, mono: true },
    {
      label: 'Coupons',
      value: `${report.coupons.generated} generated`,
      detail: `${report.coupons.available} available · ${report.coupons.redeemed} redeemed`,
    },
  ]

  return (
    <>
      <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-5">
        {cards.map((card) => (
          <div key={card.label} className="rounded border border-slate-200 bg-white px-3 py-2">
            <div className="text-xs text-slate-500">{card.label}</div>
            <div className={`mt-1 text-lg font-semibold ${card.mono ? 'font-mono' : ''}`}>{card.value}</div>
            {card.detail && <div className="text-xs text-slate-500">{card.detail}</div>}
          </div>
        ))}
      </div>

      <div className="mt-4 grid gap-4 md:grid-cols-2">
        <table className="w-full self-start overflow-hidden rounded border border-slate-200 bg-white text-left text-sm">
          <thead className="bg-slate-100 text-slate-600">
            <tr>
              <th className="px-3 py-2 font-medium">Product</th>
              <th className="px-3 py-2 text-right font-medium">Units sold</th>
            </tr>
          </thead>
          <tbody>
            {report.quantityByProduct.length === 0 && (
              <tr>
                <td colSpan={2} className="px-3 py-2 text-slate-500">
                  Nothing sold yet.
                </td>
              </tr>
            )}
            {report.quantityByProduct.map((product) => (
              <tr key={product.productId} className="border-t border-slate-100">
                <td className="px-3 py-2">{product.name}</td>
                <td className="px-3 py-2 text-right font-mono">{product.quantity}</td>
              </tr>
            ))}
          </tbody>
        </table>

        <table className="w-full self-start overflow-hidden rounded border border-slate-200 bg-white text-left text-sm">
          <thead className="bg-slate-100 text-slate-600">
            <tr>
              <th className="px-3 py-2 font-medium">Order</th>
              <th className="px-3 py-2 text-right font-medium">Subtotal</th>
              <th className="px-3 py-2 text-right font-medium">Discount</th>
              <th className="px-3 py-2 text-right font-medium">Total</th>
            </tr>
          </thead>
          <tbody>
            {orders.length === 0 && (
              <tr>
                <td colSpan={4} className="px-3 py-2 text-slate-500">
                  No orders yet.
                </td>
              </tr>
            )}
            {orders.map((order) => (
              <tr key={order.id} className="border-t border-slate-100">
                <td className="px-3 py-2">
                  <Link to={`/orders/${order.id}`} className="text-blue-600 hover:underline">
                    #{order.orderNumber}
                  </Link>
                  {order.coupon && <span className="ml-2 font-mono text-xs text-slate-500">{order.coupon.code}</span>}
                </td>
                <td className="px-3 py-2 text-right font-mono">{order.subtotal}</td>
                <td className="px-3 py-2 text-right font-mono">{order.discount}</td>
                <td className="px-3 py-2 text-right font-mono">{order.total}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}
