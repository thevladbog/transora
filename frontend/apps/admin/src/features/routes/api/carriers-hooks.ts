import { useQuery } from '@tanstack/react-query';
import { list8, type CarrierResponse } from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';

export const carriersQueryKeys = {
  all: ['carriers'] as const,
  list: () => [...carriersQueryKeys.all, 'list'] as const,
};

export function useCarriersList() {
  const { isAuthenticated } = useAuth();
  return useQuery<CarrierResponse[]>({
    queryKey: carriersQueryKeys.list(),
    queryFn: async () => (await list8()).data,
    enabled: isAuthenticated,
  });
}
