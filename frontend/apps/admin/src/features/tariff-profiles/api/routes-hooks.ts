import { useQuery } from '@tanstack/react-query';
import { list6 } from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';

export const routesQueryKeys = {
  all: ['routes'] as const,
  list: () => [...routesQueryKeys.all, 'list'] as const,
};

export function useRoutesList() {
  const { isAuthenticated } = useAuth();

  return useQuery({
    queryKey: routesQueryKeys.list(),
    queryFn: async () => (await list6()).data,
    enabled: isAuthenticated,
  });
}

export function formatRouteLabel(route: { id: string; code?: string; name: string }): string {
  const code = route.code?.trim();
  return code ? `${code} — ${route.name}` : route.name;
}
