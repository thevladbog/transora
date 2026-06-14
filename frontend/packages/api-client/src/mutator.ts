import { getAccessToken, getStationId, runLogout, runRefresh } from './auth-tokens';

async function executeRequest<T>(
  url: string,
  options: RequestInit,
  accessToken: string | null,
): Promise<T> {
  const headers = new Headers(options.headers);
  if (accessToken) {
    headers.set('Authorization', `Bearer ${accessToken}`);
  }
  const currentStationId = getStationId();
  if (currentStationId) {
    headers.set('X-Station-ID', currentStationId);
  }

  const response = await fetch(url, {
    ...options,
    headers,
  });

  if (!response.ok) {
    let detail: string | undefined;
    const text = await response.text();
    if (text) {
      try {
        const body = JSON.parse(text) as { detail?: string };
        detail = body.detail;
      } catch {
        // ignore non-JSON bodies
      }
    }
    const error = new Error(detail ?? `HTTP ${response.status}`) as Error & { status: number; detail?: string };
    error.status = response.status;
    error.detail = detail;
    throw error;
  }

  let data: unknown = undefined;
  if (response.status !== 204) {
    const text = await response.text();
    if (text) {
      data = JSON.parse(text);
    }
  }

  return {
    data,
    status: response.status,
    headers: response.headers,
  } as T;
}

export async function customInstance<T>(url: string, options: RequestInit): Promise<T> {
  const token = getAccessToken();
  try {
    return await executeRequest<T>(url, options, token);
  } catch (error) {
    const status = (error as { status?: number }).status;
    if (status !== 401 || url.includes('/auth/refresh') || url.includes('/auth/login')) {
      throw error;
    }

    const newToken = await runRefresh();
    if (!newToken) {
      runLogout();
      throw error;
    }

    return executeRequest<T>(url, options, newToken);
  }
}

export default customInstance;
