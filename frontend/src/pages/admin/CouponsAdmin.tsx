import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../api.ts'
import ErrorMessage from '../../components/ErrorMessage.tsx'
import { generateCoupon, listCoupons } from '../../couponsApi.ts'
import type { Coupon } from '../../types.ts'

const toApiError = (e: unknown) => (e instanceof ApiError ? e : new ApiError('UNKNOWN', String(e), 0))

/** Admin → Coupons: generate the next milestone coupon and see every coupon's status. */
export default function CouponsAdmin() {
  const [coupons, setCoupons] = useState<Coupon[] | null>(null)
  const [loadError, setLoadError] = useState<ApiError | null>(null)
  const [requestId, setRequestId] = useState(0)
  const [generating, setGenerating] = useState(false)
  const [result, setResult] = useState<{ coupon?: Coupon; error?: ApiError } | null>(null)

  useEffect(() => {
    let current = true
    listCoupons()
      .then((list) => {
        if (current) {
          setCoupons(list)
          setLoadError(null)
        }
      })
      .catch((e: unknown) => {
        if (current) {
          setLoadError(toApiError(e))
        }
      })
    return () => {
      current = false
    }
  }, [requestId])

  const reload = () => setRequestId((id) => id + 1)

  async function generate() {
    setGenerating(true)
    try {
      setResult({ coupon: await generateCoupon() })
      reload()
    } catch (e) {
      setResult({ error: toApiError(e) })
    } finally {
      setGenerating(false)
    }
  }

  return (
    <section>
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Coupons</h2>
        <button type="button" onClick={reload} className="text-sm text-blue-600 hover:underline">
          Reload
        </button>
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={() => void generate()}
          disabled={generating}
          className="rounded bg-slate-900 px-3 py-1.5 text-sm text-white disabled:opacity-40"
        >
          {generating ? 'Generating…' : 'Generate coupon'}
        </button>
        {result?.coupon && (
          <span role="status" className="text-sm text-emerald-800">
            Generated <span className="font-mono font-semibold">{result.coupon.code}</span> ({result.coupon.percentOff}% off) for
            order #{result.coupon.milestoneOrderNumber}
          </span>
        )}
      </div>
      {result?.error && (
        <div className="mt-3">
          <ErrorMessage error={result.error} />
        </div>
      )}
      {loadError && (
        <div className="mt-3">
          <ErrorMessage error={loadError} />
        </div>
      )}

      {coupons && coupons.length === 0 && <p className="mt-3 text-sm text-slate-500">No coupons generated yet.</p>}
      {coupons && coupons.length > 0 && (
        <table className="mt-3 w-full overflow-hidden rounded border border-slate-200 bg-white text-left text-sm">
          <thead className="bg-slate-100 text-slate-600">
            <tr>
              <th className="px-3 py-2 font-medium">Code</th>
              <th className="px-3 py-2 text-right font-medium">Off</th>
              <th className="px-3 py-2 text-right font-medium">Milestone</th>
              <th className="px-3 py-2 font-medium">Status</th>
              <th className="px-3 py-2 font-medium">Redeemed by</th>
            </tr>
          </thead>
          <tbody>
            {coupons.map((coupon) => (
              <tr key={coupon.code} className="border-t border-slate-100">
                <td className="px-3 py-2 font-mono">{coupon.code}</td>
                <td className="px-3 py-2 text-right">{coupon.percentOff}%</td>
                <td className="px-3 py-2 text-right">order #{coupon.milestoneOrderNumber}</td>
                <td className="px-3 py-2">
                  <span
                    className={`rounded px-2 py-0.5 text-xs ${
                      coupon.status === 'AVAILABLE' ? 'bg-emerald-100 text-emerald-800' : 'bg-slate-200 text-slate-700'
                    }`}
                  >
                    {coupon.status}
                  </span>
                </td>
                <td className="px-3 py-2">
                  {coupon.redeemedByOrderId ? (
                    <Link to={`/orders/${coupon.redeemedByOrderId}`} className="font-mono text-xs text-blue-600 hover:underline">
                      {coupon.redeemedByOrderId}
                    </Link>
                  ) : (
                    '—'
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
