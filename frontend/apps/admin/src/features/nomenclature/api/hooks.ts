import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createNomenclature,
  deleteNomenclature,
  listNomenclature,
  updateNomenclature,
  type UpsertNomenclatureRequest,
} from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';
import { withIsActive } from '@/lib/api-normalize';

export const nomenclatureQueryKeys = {
  all: ['admin', 'nomenclature'] as const,
  list: () => [...nomenclatureQueryKeys.all, 'list'] as const,
};

export function useNomenclatureList() {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: nomenclatureQueryKeys.list(),
    queryFn: async () => {
      const response = await listNomenclature();
      return response.data.map((item) => withIsActive(item));
    },
    enabled: isAuthenticated,
  });
}

export function useCreateNomenclature() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: UpsertNomenclatureRequest) => (await createNomenclature(payload)).data,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: nomenclatureQueryKeys.all }),
  });
}

export function useUpdateNomenclature() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, payload }: { id: string; payload: UpsertNomenclatureRequest }) =>
      (await updateNomenclature(id, payload)).data,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: nomenclatureQueryKeys.all }),
  });
}

export function useDeleteNomenclature() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await deleteNomenclature(id);
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: nomenclatureQueryKeys.all }),
  });
}
