const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

/** Mirrors the backend error body: { code, message, details }. */
export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly details: Record<string, unknown>

  constructor(code: string, message: string, status: number, details: Record<string, unknown> = {}) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.details = details
  }
}

export interface ApiResponse<T> {
  data: T
  status: number
  headers: Headers
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  headers?: Record<string, string>
}

/**
 * Calls the backend and returns the parsed body with status and headers
 * (checkout needs both to tell a new order from a replayed one).
 * Every failure is thrown as an ApiError so screens can branch on `code`.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
  } catch {
    throw new ApiError('NETWORK_ERROR', `Cannot reach the API at ${API_BASE_URL}`, 0)
  }

  const body = await readJson(response)

  if (!response.ok) {
    if (isErrorBody(body)) {
      throw new ApiError(body.code, body.message, response.status, body.details ?? {})
    }
    throw new ApiError(`HTTP_${response.status}`, response.statusText || 'Request failed', response.status)
  }

  return { data: body as T, status: response.status, headers: response.headers }
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) {
    return null
  }
  try {
    return JSON.parse(text)
  } catch {
    return null
  }
}

function isErrorBody(body: unknown): body is { code: string; message: string; details?: Record<string, unknown> } {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as { code?: unknown }).code === 'string' &&
    typeof (body as { message?: unknown }).message === 'string'
  )
}
