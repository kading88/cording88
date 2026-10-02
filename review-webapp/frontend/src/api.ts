export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) { super(message); }
}
let csrf: { token: string; headerName: string } | null = null;

export async function refreshCsrf() {
  const response = await fetch('/api/auth/csrf', { credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw new ApiError(response.status, 'NETWORK_ERROR', 'Could not fetch CSRF token');
  csrf = await response.json();
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  const method = options.method ?? 'GET';
  if (!['GET', 'HEAD'].includes(method)) {
    if (!csrf) await refreshCsrf();
    headers.set(csrf!.headerName, csrf!.token);
    if (!(options.body instanceof URLSearchParams)) headers.set('Content-Type', 'application/json');
  }
  const response = await fetch(`/api${path}`, { ...options, headers, credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    if (response.status === 401 && !['/auth/login', '/auth/me'].includes(path)) {
      csrf = null;
      window.dispatchEvent(new Event('session-expired'));
    }
    throw new ApiError(response.status, body.code ?? 'ERROR', body.message ?? 'The request could not be completed. Please try again.');
  }
  return response.status === 204 ? undefined as T : response.json();
}

export type User = { id: number; username: string; role: 'ADMIN' | 'REVIEWER' };
export type Label = 'NORMAL' | 'ATTACK';
export type Row = {
  id: number; sourceRow: number; predictedLabel: Label; score: number;
  reviewedLabel: Label | null; reviewedBy: string | null; updatedAt: string | null; lockedBy: string | null;
};
export type Detail = Row & {
  features: Record<string, number | null>; note: string; modelVersion: string;
  somX: number; somY: number; quantizationError: number; neuronCount: number; clusterPurity: number;
};
export type Lease = { record: Detail; lockToken: string; expiresAt: string };
export type Stats = { total: number; pending: number; reviewed: number; corrected: number };
