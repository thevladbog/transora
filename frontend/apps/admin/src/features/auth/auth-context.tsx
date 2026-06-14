import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  clearTokens,
  getAccessToken,
  getRefreshToken,
  login,
  logout,
  me,
  refresh,
  setRefreshHandler,
  setLogoutHandler,
  setStationId,
  setTokenPair,
  type MeResponse,
} from '@transora/api-client';

type AuthContextValue = {
  user: MeResponse | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  loginWithCredentials: (loginName: string, password: string) => Promise<void>;
  logoutUser: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

async function fetchMe(): Promise<MeResponse> {
  const response = await me();
  return response.data;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [hasSession, setHasSession] = useState(() => getRefreshToken() !== null);
  const [isBootstrapping, setIsBootstrapping] = useState(() => getRefreshToken() !== null);

  const clearSession = useCallback(() => {
    clearTokens();
    setHasSession(false);
    queryClient.removeQueries({ queryKey: ['auth', 'me'] });
  }, [queryClient]);

  const performRefresh = useCallback(async (): Promise<string | null> => {
    const refreshToken = getRefreshToken();
    if (!refreshToken) {
      return null;
    }

    try {
      const response = await refresh({ refreshToken });
      const tokens = response.data;
      setTokenPair(tokens.accessToken, tokens.refreshToken);
      setHasSession(true);
      return tokens.accessToken;
    } catch {
      clearSession();
      return null;
    }
  }, [clearSession]);

  useLayoutEffect(() => {
    setRefreshHandler(performRefresh);
    setLogoutHandler(clearSession);
    return () => {
      setRefreshHandler(null);
      setLogoutHandler(null);
    };
  }, [performRefresh, clearSession]);

  useEffect(() => {
    if (!isBootstrapping) {
      return;
    }

    let cancelled = false;

    async function bootstrap() {
      try {
        if (!getRefreshToken()) {
          if (!cancelled) {
            setHasSession(false);
          }
          return;
        }

        if (!getAccessToken()) {
          const token = await performRefresh();
          if (!token || cancelled) {
            return;
          }
        }

        await queryClient.fetchQuery({
          queryKey: ['auth', 'me'],
          queryFn: fetchMe,
          retry: false,
        });
      } catch {
        if (!cancelled) {
          clearSession();
        }
      } finally {
        if (!cancelled) {
          setIsBootstrapping(false);
        }
      }
    }

    void bootstrap();

    return () => {
      cancelled = true;
    };
  }, [clearSession, isBootstrapping, performRefresh, queryClient]);

  const meQuery = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: fetchMe,
    enabled: hasSession && !isBootstrapping,
    retry: false,
  });

  useEffect(() => {
    if (meQuery.isError && hasSession) {
      clearSession();
    }
  }, [clearSession, hasSession, meQuery.isError]);

  const loginWithCredentials = useCallback(
    async (loginName: string, password: string) => {
      const response = await login({ login: loginName, password });
      const tokens = response.data;
      setTokenPair(tokens.accessToken, tokens.refreshToken);
      setHasSession(true);
      setIsBootstrapping(false);
      await queryClient.fetchQuery({
        queryKey: ['auth', 'me'],
        queryFn: fetchMe,
        retry: false,
      });
      const meData = queryClient.getQueryData<MeResponse>(['auth', 'me']);
      setStationId(meData?.stationId ?? null);
    },
    [queryClient],
  );

  const logoutUser = useCallback(async () => {
    const refreshToken = getRefreshToken();
    try {
      if (refreshToken) {
        await logout({ refreshToken });
      }
    } finally {
      clearSession();
    }
  }, [clearSession]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user: meQuery.data ?? null,
      isAuthenticated: hasSession && meQuery.isSuccess,
      isLoading: isBootstrapping || (hasSession && meQuery.isPending),
      loginWithCredentials,
      logoutUser,
    }),
    [
      hasSession,
      isBootstrapping,
      loginWithCredentials,
      logoutUser,
      meQuery.data,
      meQuery.isPending,
      meQuery.isSuccess,
    ],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
}
