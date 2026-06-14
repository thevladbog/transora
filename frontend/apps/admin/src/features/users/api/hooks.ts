import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  activate,
  assign,
  changePassword,
  create10,
  deactivate,
  get12,
  list9,
  revokeAssignment,
  type AssignmentRequest,
  type ChangePasswordRequest,
  type CreateUserRequest,
} from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';

export const usersQueryKeys = {
  all: ['admin', 'users'] as const,
  list: () => [...usersQueryKeys.all, 'list'] as const,
  detail: (userId: string) => [...usersQueryKeys.all, 'detail', userId] as const,
};

export function useUsersList() {
  const { isAuthenticated } = useAuth();

  return useQuery({
    queryKey: usersQueryKeys.list(),
    queryFn: async () => {
      const response = await list9();
      return response.data;
    },
    enabled: isAuthenticated,
  });
}

export function useUser(userId: string | undefined) {
  const { isAuthenticated } = useAuth();

  return useQuery({
    queryKey: usersQueryKeys.detail(userId ?? ''),
    queryFn: async () => {
      const response = await get12(userId!);
      return response.data;
    },
    enabled: isAuthenticated && Boolean(userId),
  });
}

export function useCreateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: CreateUserRequest) => {
      const response = await create10(payload);
      return response.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: usersQueryKeys.all });
    },
  });
}

export function useDeactivateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (userId: string) => {
      await deactivate(userId);
    },
    onSuccess: (_data, userId) => {
      void queryClient.invalidateQueries({ queryKey: usersQueryKeys.all });
      void queryClient.invalidateQueries({ queryKey: usersQueryKeys.detail(userId) });
    },
  });
}

export function useActivateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (userId: string) => {
      await activate(userId);
    },
    onSuccess: (_data, userId) => {
      void queryClient.invalidateQueries({ queryKey: usersQueryKeys.all });
      void queryClient.invalidateQueries({ queryKey: usersQueryKeys.detail(userId) });
    },
  });
}

export function useChangeUserPassword() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      userId,
      payload,
    }: {
      userId: string;
      payload: ChangePasswordRequest;
    }) => {
      await changePassword(userId, payload);
    },
    onSuccess: (_data, { userId }) => {
      void queryClient.invalidateQueries({ queryKey: usersQueryKeys.detail(userId) });
    },
  });
}

export function useAssignRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      userId,
      payload,
    }: {
      userId: string;
      payload: AssignmentRequest;
    }) => {
      const response = await assign(userId, payload);
      return response.data;
    },
    onSuccess: (_data, { userId }) => {
      void queryClient.invalidateQueries({ queryKey: usersQueryKeys.detail(userId) });
    },
  });
}

export function useRevokeUserAssignment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      userId,
      assignmentId,
    }: {
      userId: string;
      assignmentId: string;
    }) => {
      await revokeAssignment(userId, assignmentId);
    },
    onSuccess: (_data, { userId }) => {
      void queryClient.invalidateQueries({ queryKey: usersQueryKeys.detail(userId) });
    },
  });
}
