import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createCommercePolicy,
  deleteCommercePolicy,
  getRoutePolicies,
  listCommercePolicies,
  replaceRoutePolicies,
  updateCommercePolicy,
  type RoutePolicyBindingRequest,
  type UpsertCommercePolicyRequest,
} from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';
import { withIsActive } from '@/lib/api-normalize';

export const policiesQueryKeys = {
  all: ['admin', 'policies'] as const,
  list: (policyType?: string) => [...policiesQueryKeys.all, 'list', policyType ?? 'all'] as const,
  route: (routeId: string) => [...policiesQueryKeys.all, 'route', routeId] as const,
};

export function usePoliciesList(policyType?: string) {
  const { isAuthenticated } = useAuth();

  return useQuery({
    queryKey: policiesQueryKeys.list(policyType),
    queryFn: async () => {
      const response = await listCommercePolicies(policyType);
      return response.data.map((item) => withIsActive(item));
    },
    enabled: isAuthenticated,
  });
}

/** @deprecated Use usePoliciesList */
export function useRefundPoliciesList() {
  return usePoliciesList();
}

export function useCreatePolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: UpsertCommercePolicyRequest) => {
      const response = await createCommercePolicy(payload);
      return withIsActive(response.data);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: policiesQueryKeys.all });
    },
  });
}

export function useUpdatePolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, payload }: { id: string; payload: UpsertCommercePolicyRequest }) => {
      const response = await updateCommercePolicy(id, payload);
      return withIsActive(response.data);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: policiesQueryKeys.all });
    },
  });
}

export function useDeletePolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (id: string) => {
      await deleteCommercePolicy(id);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: policiesQueryKeys.all });
    },
  });
}

/** @deprecated Use useCreatePolicy */
export const useCreateRefundPolicy = useCreatePolicy;
/** @deprecated Use useUpdatePolicy */
export const useUpdateRefundPolicy = useUpdatePolicy;
/** @deprecated Use useDeletePolicy */
export const useDeleteRefundPolicy = useDeletePolicy;

export function useRoutePolicies(routeId: string | undefined) {
  const { isAuthenticated } = useAuth();
  return useQuery({
    queryKey: policiesQueryKeys.route(routeId ?? ''),
    queryFn: async () => (await getRoutePolicies(routeId!)).data,
    enabled: isAuthenticated && Boolean(routeId),
  });
}

export function useReplaceRoutePolicies(routeId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (policies: RoutePolicyBindingRequest[]) =>
      (await replaceRoutePolicies(routeId, policies)).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: policiesQueryKeys.route(routeId) });
    },
  });
}
