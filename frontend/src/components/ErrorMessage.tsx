import type { ApiError } from '../api.ts'

/** Shows an API error with its machine code and any per-field messages from `details.fields`. */
export default function ErrorMessage({ error }: { error: ApiError }) {
  const fields = error.details.fields as Record<string, string> | undefined
  return (
    <div role="alert" className="rounded border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800">
      <span className="font-mono text-xs font-semibold">{error.code}</span> {error.message}
      {fields && (
        <ul className="mt-1 list-disc pl-5">
          {Object.entries(fields).map(([field, message]) => (
            <li key={field}>
              <span className="font-mono">{field}</span>: {message}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
