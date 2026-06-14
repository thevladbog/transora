import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createPoint,
  deletePoint,
  listPoints,
  updatePoint,
  type UpsertPointRequest,
} from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';
import { withIsActive } from '@/lib/api-normalize';

export const pointsQueryKeys = {
  all: ['admin', 'points'] as const,
  list: () => [...pointsQueryKeys.all, 'list'] as const,
};

export function usePointsList() {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: pointsQueryKeys.list(),
    queryFn: async () => {
      const response = await listPoints();
      return response.data.map((item) => withIsActive(item));
    },
    enabled: isAuthenticated,
  });
}

export function useCreatePoint() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: UpsertPointRequest) => (await createPoint(payload)).data,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: pointsQueryKeys.all }),
  });
}

export function useUpdatePoint() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, payload }: { id: string; payload: UpsertPointRequest }) =>
      (await updatePoint(id, payload)).data,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: pointsQueryKeys.all }),
  });
}

export function useDeletePoint() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await deletePoint(id);
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: pointsQueryKeys.all }),
  });
}
