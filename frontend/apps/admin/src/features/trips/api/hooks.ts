import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createFromRoute,
  listVehicles,
  type CreateTripFromRouteRequest,
  type TripResponse,
} from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';

export const tripsQueryKeys = {
  all: ['trips'] as const,
  list: (stationCode?: string) => [...tripsQueryKeys.all, 'list', stationCode] as const,
};

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
