import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createSchedule,
  generate,
  getSchedule,
  listSchedules,
  updateSchedule,
  type CreateScheduleRequest,
  type GenerateParams,
  type TripGenerationResult,
  type UpdateScheduleRequest,
} from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';

export const schedulesQueryKeys = {
  all: ['schedules'] as const,
  list: () => [...schedulesQueryKeys.all, 'list'] as const,
  detail: (id: string) => [...schedulesQueryKeys.all, 'detail', id] as const,
};

export function useSchedulesList() {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: schedulesQueryKeys.list(),
    queryFn: async () => (await listSchedules()).data,
    enabled: isAuthenticated,
  });
}

export function useSchedule(scheduleId: string) {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: schedulesQueryKeys.detail(scheduleId),
    queryFn: async () => (await getSchedule(scheduleId)).data,
    enabled: isAuthenticated && Boolean(scheduleId),
  });
}

export function useCreateSchedule() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: CreateScheduleRequest) => (await createSchedule(payload)).data,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: schedulesQueryKeys.all }),
  });
}

export function useUpdateSchedule(scheduleId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: UpdateScheduleRequest) => (await updateSchedule(scheduleId, payload)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: schedulesQueryKeys.all });
      void queryClient.invalidateQueries({ queryKey: schedulesQueryKeys.detail(scheduleId) });
    },
  });
}

export function useGenerateTrips() {
  return useMutation({
    mutationFn: async (params?: GenerateParams): Promise<TripGenerationResult> =>
      (await generate(params)).data as TripGenerationResult,
  });
}
