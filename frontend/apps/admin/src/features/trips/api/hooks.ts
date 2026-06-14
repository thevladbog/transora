import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createFromRoute,
  getTrip,
  listTrips,
  listVehicles,
  updateTrip,
  type CreateTripFromRouteRequest,
  type TripDetailResponse,
  type TripResponse,
  type UpdateTripRequest,
} from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';

export const tripsQueryKeys = {
  all: ['trips'] as const,
  list: (stationCode?: string) => [...tripsQueryKeys.all, 'list', stationCode] as const,
  detail: (tripId: string, includeStops?: boolean) =>
    [...tripsQueryKeys.all, 'detail', tripId, includeStops ? 'stops' : 'trip'] as const,
};

export function useTripsList(stationCode?: string) {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: tripsQueryKeys.list(stationCode),
    queryFn: async () =>
      (await listTrips({ stationCode, limit: 100, horizonHours: 24 })).data,
    enabled: isAuthenticated && Boolean(stationCode),
  });
}

export function useTrip(tripId: string | null, options?: { includeStops?: boolean }) {
  const { isAuthenticated } = useAuth();
  const includeStops = options?.includeStops ?? false;
  return useQuery({
    queryKey: tripsQueryKeys.detail(tripId ?? '', includeStops),
    queryFn: async (): Promise<TripDetailResponse> =>
      (
        await getTrip(tripId!, includeStops ? { include: 'stops' } : undefined)
      ).data,
    enabled: isAuthenticated && Boolean(tripId),
  });
}

export function useUpdateTrip(stationCode?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      tripId,
      payload,
    }: {
      tripId: string;
      payload: UpdateTripRequest;
    }): Promise<TripResponse> => (await updateTrip(tripId, payload)).data,
    onSuccess: (_, { tripId }) => {
      void queryClient.invalidateQueries({ queryKey: tripsQueryKeys.all });
      if (stationCode) {
        void queryClient.invalidateQueries({ queryKey: tripsQueryKeys.list(stationCode) });
      }
      void queryClient.invalidateQueries({ queryKey: tripsQueryKeys.detail(tripId, true) });
      void queryClient.invalidateQueries({ queryKey: tripsQueryKeys.detail(tripId, false) });
    },
  });
}

export function useCreateTripFromRoute(stationCode?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: CreateTripFromRouteRequest): Promise<TripResponse> =>
      (await createFromRoute(payload)).data as TripResponse,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: tripsQueryKeys.all });
      if (stationCode) {
        void queryClient.invalidateQueries({ queryKey: tripsQueryKeys.list(stationCode) });
      }
    },
  });
}

export function useVehiclesList(enabled = true) {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: ['vehicles', 'list'],
    queryFn: async () => (await listVehicles()).data,
    enabled: isAuthenticated && enabled,
  });
}
