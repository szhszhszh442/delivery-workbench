export interface CurrentUser {
  id: string
  loginName: string
  displayName: string
}

interface CsrfToken {
  token: string
  headerName: string
}

export class ApiError extends Error {
  constructor(readonly status: number, message: string, readonly code?: string,
    readonly details?: Record<string, unknown>) {
    super(message)
  }
}

export async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`/api/v1${path}`, {
    ...options,
    credentials: 'same-origin',
    signal: AbortSignal.timeout(8000),
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok || payload?.data === undefined) {
    throw new ApiError(response.status, payload?.error?.message ?? '暂时无法完成请求',
      payload?.error?.code, payload?.error?.details)
  }
  return payload.data as T
}

export async function writeJson<T>(path: string, body: object, key?: string, method: 'POST' | 'PATCH' | 'PUT' = 'POST'): Promise<T> {
  const csrf = await request<CsrfToken>('/auth/csrf')
  return request<T>(path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      [csrf.headerName]: csrf.token,
      ...(key === undefined ? {} : { 'Idempotency-Key': key }),
    },
    body: JSON.stringify(body),
  })
}

export function getCurrentUser(): Promise<CurrentUser> {
  return request('/me')
}

export function login(loginName: string, password: string): Promise<CurrentUser> {
  return writeJson('/auth/login', { loginName, password })
}

export function logout(): Promise<{ loggedOut: boolean }> {
  return writeJson('/auth/logout', {})
}
