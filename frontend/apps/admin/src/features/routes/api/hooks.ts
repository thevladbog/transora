import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createRoutePricing,
  deactivateRoutePricing,
  getRoutePricing,
  listRoutePricing,
  syncRouteStops,
  updateRoutePricing,
  upsertRouteMatrix,
  type CreateRoutePricingRequest,
  type SyncRouteStopsRequest,
  type TariffCellRequest,
  type UpdateRoutePricingRequest,
} from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';

export const routesQueryKeys = {
  all: ['admin', 'route-pricing'] as const,
  list: () => [...routesQueryKeys.all, 'list'] as const,
  detail: (id: string) => [...routesQueryKeys.all, 'detail', id] as const,
};

export function useRoutesPricingList() {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: routesQueryKeys.list(),
    queryFn: async () => (await listRoutePricing()).data,
    enabled: isAuthenticated,
  });
}

export function useRoutePricing(routeId: string) {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: routesQueryKeys.detail(routeId),
    queryFn: async () => (await getRoutePricing(routeId)).data,
    enabled: isAuthenticated && Boolean(routeId),
  });
}

export function useCreateRoutePricing() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: CreateRoutePricingRequest) => (await createRoutePricing(payload)).data,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: routesQueryKeys.all }),
  });
}

export function useUpdateRoutePricing(routeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: UpdateRoutePricingRequest) => (await updateRoutePricing(routeId, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: routesQueryKeys.all });
      void queryClient.invalidateQueries({ queryKey: routesQueryKeys.detail(routeId) });
    },
  });
}

export function useSyncRouteStops(routeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: SyncRouteStopsRequest) => (await syncRouteStops(routeId, payload)).data,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: routesQueryKeys.detail(routeId) }),
  });
}

export function useUpsertRouteMatrix(routeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (cells: TariffCellRequest[]) => (await upsertRouteMatrix(routeId, { cells })).data,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: routesQueryKeys.detail(routeId) }),
  });
}

export function useDeactivateRoutePricing() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (routeId: string) => (await deactivateRoutePricing(routeId)).data,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: routesQueryKeys.all }),
  });
}
