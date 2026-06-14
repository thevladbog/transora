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
  getRefreshToken,
  me,
  meStations,
  setStationId,
  setTokenPair,
  switchStation,
  type MeResponse,
  type UserStationResponse,
} from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';
import { isStationTier } from '@/lib/app-tier';

type StationContextValue = {
  stations: UserStationResponse[];
  currentStation: UserStationResponse | null;
  currentStationId: string | null;
  isLoading: boolean;
  switchToStation: (stationId: string) => Promise<void>;
};

const StationContext = createContext<StationContextValue | null>(null);

async function fetchStations(): Promise<UserStationResponse[]> {
  const response = await meStations();
  return response.data;
}

async function fetchMe(): Promise<MeResponse> {
  const response = await me();
  return response.data;
}

export function StationProvider({ children }: { children: ReactNode }) {
  const { isAuthenticated, user } = useAuth();
  const queryClient = useQueryClient();
  const stationTier = isStationTier();
  const [selectedStationId, setSelectedStationId] = useState<string | null>(null);

  const stationsQuery = useQuery({
    queryKey: ['auth', 'stations'],
    queryFn: fetchStations,
    enabled: stationTier && isAuthenticated,
    retry: false,
  });

  const switchToStation = useCallback(
    async (stationId: string) => {
      const refreshToken = getRefreshToken();
      const response = await switchStation({ stationId, refreshToken: refreshToken ?? undefined });
      const tokens = response.data;
      setTokenPair(tokens.accessToken, tokens.refreshToken);
      setSelectedStationId(stationId);
      setStationId(stationId);
      await queryClient.fetchQuery({ queryKey: ['auth', 'me'], queryFn: fetchMe, retry: false });
    },
    [queryClient],
  );

  useEffect(() => {
    if (!stationTier || !isAuthenticated) {
      setSelectedStationId(null);
      setStationId(null);
      return;
    }

    const stations = stationsQuery.data ?? [];
    if (stations.length === 0) {
      setSelectedStationId(null);
      setStationId(null);
      return;
    }

    if (!user?.stationId && stations.length > 0 && !selectedStationId) {
      void switchToStation(stations[0].stationId);
      return;
    }

    const fromUser = user?.stationId;
    const resolved =
      (fromUser && stations.some((s) => s.stationId === fromUser) ? fromUser : null) ??
      selectedStationId ??
      stations[0]?.stationId ??
      null;

    if (resolved && resolved !== selectedStationId) {
      setSelectedStationId(resolved);
    }
    setStationId(resolved);
  }, [
    isAuthenticated,
    selectedStationId,
    stationTier,
    stationsQuery.data,
    switchToStation,
    user?.stationId,
  ]);

  const currentStation = useMemo(() => {
    const stations = stationsQuery.data ?? [];
    return stations.find((s) => s.stationId === selectedStationId) ?? null;
  }, [selectedStationId, stationsQuery.data]);

  const value = useMemo<StationContextValue>(
    () => ({
      stations: stationsQuery.data ?? [],
      currentStation,
      currentStationId: selectedStationId,
      isLoading: stationTier && isAuthenticated && stationsQuery.isPending,
      switchToStation,
    }),
    [
      currentStation,
      isAuthenticated,
      selectedStationId,
      stationTier,
      stationsQuery.data,
      stationsQuery.isPending,
      switchToStation,
    ],
  );

  return <StationContext.Provider value={value}>{children}</StationContext.Provider>;
}

export function useStationContext(): StationContextValue {
  const context = useContext(StationContext);
  if (!context) {
    throw new Error('useStationContext must be used within StationProvider');
  }
  return context;
}
