import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  clearTokens,
  getRefreshToken,
  login,
  logout,
  me,
  refresh,
  setRefreshHandler,
  setLogoutHandler,
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

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [hasSession, setHasSession] = useState(() => getRefreshToken() !== null);

  const meQuery = useQuery({
    queryKey: ['auth', 'me'],
    queryFn: async () => {
      const response = await me();
      return response.data;
    },
    enabled: hasSession,
    retry: false,
  });

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
      await queryClient.invalidateQueries({ queryKey: ['auth', 'me'] });
      return tokens.accessToken;
    } catch {
      clearTokens();
      setHasSession(false);
      queryClient.removeQueries({ queryKey: ['auth', 'me'] });
      return null;
    }
  }, [queryClient]);

  useEffect(() => {
    setRefreshHandler(performRefresh);
    setLogoutHandler(() => {
      clearTokens();
      setHasSession(false);
      queryClient.removeQueries({ queryKey: ['auth', 'me'] });
    });
    return () => {
      setRefreshHandler(null);
      setLogoutHandler(null);
    };
  }, [performRefresh, queryClient]);

  const loginWithCredentials = useCallback(
    async (loginName: string, password: string) => {
      const response = await login({ login: loginName, password });
      const tokens = response.data;
      setTokenPair(tokens.accessToken, tokens.refreshToken);
      setHasSession(true);
      await queryClient.invalidateQueries({ queryKey: ['auth', 'me'] });
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
      clearTokens();
      setHasSession(false);
      queryClient.removeQueries({ queryKey: ['auth', 'me'] });
    }
  }, [queryClient]);

  const value = useMemo<AuthContextValue>(
    () => ({
      user: meQuery.data ?? null,
      isAuthenticated: hasSession && meQuery.isSuccess,
      isLoading: hasSession && meQuery.isLoading,
      loginWithCredentials,
      logoutUser,
    }),
    [hasSession, loginWithCredentials, logoutUser, meQuery.data, meQuery.isLoading, meQuery.isSuccess],
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
