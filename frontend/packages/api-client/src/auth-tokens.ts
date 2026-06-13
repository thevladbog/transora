const REFRESH_TOKEN_KEY = 'transora_refresh_token';

let accessToken: string | null = null;
let refreshHandler: (() => Promise<string | null>) | null = null;
let logoutHandler: (() => void) | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

export function getRefreshToken(): string | null {
  return sessionStorage.getItem(REFRESH_TOKEN_KEY);
}

export function setRefreshToken(token: string | null): void {
  if (token) {
    sessionStorage.setItem(REFRESH_TOKEN_KEY, token);
  } else {
    sessionStorage.removeItem(REFRESH_TOKEN_KEY);
  }
}

export function setTokenPair(access: string, refresh: string): void {
  setAccessToken(access);
  setRefreshToken(refresh);
}

export function clearTokens(): void {
  setAccessToken(null);
  setRefreshToken(null);
}

export function setRefreshHandler(handler: (() => Promise<string | null>) | null): void {
  refreshHandler = handler;
}

export function setLogoutHandler(handler: (() => void) | null): void {
  logoutHandler = handler;
}

export async function runRefresh(): Promise<string | null> {
  if (!refreshHandler) {
    return null;
  }
  return refreshHandler();
}

export function runLogout(): void {
  logoutHandler?.();
}
