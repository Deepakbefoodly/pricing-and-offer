import { useState } from 'react'
import { ApiError } from '../api.ts'
import { checkoutCart, type CheckoutRequest } from '../ordersApi.ts'
import ErrorMessage from './ErrorMessage.tsx'

type Outcome = { kind: 'replayed'; status: number; replayed: boolean; orderId: string; sameOrder: boolean } | { kind: 'error'; error: ApiError }

/**
 * Resends the checkout that placed this order, to show what a client sees when it retries after a lost
 * response (same key → the same order back) versus a genuinely new attempt (new key → refused).
 */
export default function RetryDemo({ request, orderId }: { request: CheckoutRequest; orderId: string }) {
  const [outcome, setOutcome] = useState<Outcome | null>(null)
  const [sending, setSending] = useState(false)

  async function send(idempotencyKey: string) {
    setSending(true)
    try {
      const response = await checkoutCart({ ...request, idempotencyKey })
      setOutcome({
        kind: 'replayed',
        status: response.status,
        replayed: response.replayed,
        orderId: response.order.id,
        sameOrder: response.order.id === orderId,
      })
    } catch (e) {
      setOutcome({ kind: 'error', error: e instanceof ApiError ? e : new ApiError('UNKNOWN', String(e), 0) })
    } finally {
      setSending(false)
    }
  }

  const button = 'rounded border border-slate-300 bg-white px-3 py-1.5 text-sm hover:bg-slate-50 disabled:opacity-40'
  return (
    <section className="mt-6 rounded border border-dashed border-slate-300 bg-white p-4">
      <h2 className="text-sm font-semibold">Retry demo</h2>
      <p className="mt-1 text-sm text-slate-600">
        Resend the checkout request that placed this order, as a client would after a timeout.
        Idempotency-Key: <span className="font-mono text-xs">{request.idempotencyKey}</span>
      </p>
      <div className="mt-3 flex flex-wrap gap-2">
        <button type="button" disabled={sending} onClick={() => void send(request.idempotencyKey)} className={button}>
          Simulate retry (same key)
        </button>
        <button type="button" disabled={sending} onClick={() => void send(crypto.randomUUID())} className={button}>
          New attempt (new key)
        </button>
      </div>
      {outcome?.kind === 'replayed' && (
        <p role="status" className="mt-3 rounded border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-900">
          HTTP {outcome.status}
          {outcome.replayed && ' · Idempotent-Replayed: true'} ·{' '}
          {outcome.sameOrder ? 'the same order came back; nothing was charged or placed again.' : `a different order (${outcome.orderId})!`}
        </p>
      )}
      {outcome?.kind === 'error' && (
        <div className="mt-3">
          <ErrorMessage error={outcome.error} />
        </div>
      )}
    </section>
  )
}
