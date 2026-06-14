import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createAdminStation,
  createStationProvisioningToken,
  forceStationAgentSync,
  getAdminStation,
  getStationAgentStatus,
  listAdminStations,
  listStationDisplayBoards,
  updateAdminStation,
  type CreateAdminStationRequest,
  type UpdateAdminStationRequest,
} from '@transora/api-client';

export const stationsQueryKeys = {
  all: ['admin', 'stations'] as const,
  list: () => [...stationsQueryKeys.all, 'list'] as const,
  detail: (id: string) => [...stationsQueryKeys.all, 'detail', id] as const,
  status: (id: string) => [...stationsQueryKeys.all, 'status', id] as const,
  displayBoards: (id: string) => [...stationsQueryKeys.all, 'display-boards', id] as const,
};

export function useStationsList() {
  return useQuery({
    queryKey: stationsQueryKeys.list(),
    queryFn: async () => {
      const response = await listAdminStations();
      return response.data;
    },
  });
}

export function useStation(stationId: string | undefined) {
  return useQuery({
    queryKey: stationsQueryKeys.detail(stationId ?? ''),
    queryFn: async () => {
      const response = await getAdminStation(stationId!);
      return response.data;
    },
    enabled: Boolean(stationId),
  });
}

export function useStationAgentStatus(stationId: string | undefined, refetchInterval?: number) {
  return useQuery({
    queryKey: stationsQueryKeys.status(stationId ?? ''),
    queryFn: async () => {
      const response = await getStationAgentStatus(stationId!);
      return response.data;
    },
    enabled: Boolean(stationId),
    refetchInterval,
  });
}

export function useCreateStation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: CreateAdminStationRequest) => {
      const response = await createAdminStation(payload);
      return response.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: stationsQueryKeys.all });
    },
  });
}

export function useUpdateStation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, payload }: { id: string; payload: UpdateAdminStationRequest }) => {
      const response = await updateAdminStation(id, payload);
      return response.data;
    },
    onSuccess: (_data, { id }) => {
      void queryClient.invalidateQueries({ queryKey: stationsQueryKeys.all });
      void queryClient.invalidateQueries({ queryKey: stationsQueryKeys.detail(id) });
    },
  });
}

export function useCreateProvisioningToken() {
  return useMutation({
    mutationFn: async (stationId: string) => {
      const response = await createStationProvisioningToken(stationId);
      return response.data;
    },
  });
}

export function useForceAgentSync() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (stationId: string) => {
      await forceStationAgentSync(stationId);
    },
    onSuccess: (_data, stationId) => {
      void queryClient.invalidateQueries({ queryKey: stationsQueryKeys.status(stationId) });
      void queryClient.invalidateQueries({ queryKey: stationsQueryKeys.list() });
    },
  });
}

export function useStationDisplayBoards(stationId: string | undefined) {
  return useQuery({
    queryKey: stationsQueryKeys.displayBoards(stationId ?? ''),
    queryFn: async () => {
      const response = await listStationDisplayBoards(stationId!);
      return response.data;
    },
    enabled: Boolean(stationId),
  });
}
