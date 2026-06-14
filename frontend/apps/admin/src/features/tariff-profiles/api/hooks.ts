import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createTariffProfile,
  deleteTariffProfile,
  getTariffMatrix,
  getTariffProfile,
  listTariffProfiles,
  replaceTariffStops,
  updateTariffProfile,
  upsertTariffMatrix,
  type TariffCellRequest,
  type UpsertTariffProfileRequest,
} from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';
import { withIsActive } from '@/lib/api-normalize';

export const tariffProfilesQueryKeys = {
  all: ['admin', 'tariff-profiles'] as const,
  list: () => [...tariffProfilesQueryKeys.all, 'list'] as const,
  detail: (id: string) => [...tariffProfilesQueryKeys.all, 'detail', id] as const,
  matrix: (id: string) => [...tariffProfilesQueryKeys.all, 'matrix', id] as const,
};

export function useTariffProfilesList() {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: tariffProfilesQueryKeys.list(),
    queryFn: async () => {
      const response = await listTariffProfiles();
      return response.data.map((item) => withIsActive(item));
    },
    enabled: isAuthenticated,
  });
}

export function useTariffMatrix(profileId: string) {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: tariffProfilesQueryKeys.matrix(profileId),
    queryFn: async () => (await getTariffMatrix(profileId)).data,
    enabled: isAuthenticated && Boolean(profileId),
  });
}

export function useTariffProfile(profileId: string) {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: tariffProfilesQueryKeys.detail(profileId),
    queryFn: async () => withIsActive((await getTariffProfile(profileId)).data),
    enabled: isAuthenticated && Boolean(profileId),
  });
}

export function useCreateTariffProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: UpsertTariffProfileRequest) =>
      withIsActive((await createTariffProfile(payload)).data),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: tariffProfilesQueryKeys.all }),
  });
}

export function useUpdateTariffProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, payload }: { id: string; payload: UpsertTariffProfileRequest }) =>
      withIsActive((await updateTariffProfile(id, payload)).data),
    onSuccess: (_, { id }) => {
      void queryClient.invalidateQueries({ queryKey: tariffProfilesQueryKeys.all });
      void queryClient.invalidateQueries({ queryKey: tariffProfilesQueryKeys.detail(id) });
    },
  });
}

export function useReplaceTariffStops(profileId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (pointIds: string[]) => (await replaceTariffStops(profileId, pointIds)).data,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: tariffProfilesQueryKeys.matrix(profileId) }),
  });
}

export function useUpsertTariffMatrix(profileId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (cells: TariffCellRequest[]) => (await upsertTariffMatrix(profileId, cells)).data,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: tariffProfilesQueryKeys.matrix(profileId) }),
  });
}

export function useDeleteTariffProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await deleteTariffProfile(id);
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: tariffProfilesQueryKeys.all }),
  });
}
