import { Chip } from '@heroui/react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { list1 } from '@transora/api-client';
import { useAuth } from '@/features/auth/auth-context';
import { useStationAgentStatus } from '@/features/stations/api/hooks';
import { stationsQueryKeys } from '@/features/stations/api/hooks';
import { listAdminStations } from '@transora/api-client';
import { useStationContext } from '@/features/stations/station-context';
import { isNetworkTier, isStationTier } from '@/lib/app-tier';

export function DashboardPage() {
  const { user } = useAuth();
  const { t } = useTranslation('dashboard');
  const stationTier = isStationTier();
  const networkTier = isNetworkTier();
  const { currentStationId, currentStation } = useStationContext();
  const { data: agentStatus } = useStationAgentStatus(
    stationTier ? currentStationId ?? undefined : undefined,
    15_000,
  );
  const { data: stations } = useQuery({
    queryKey: stationsQueryKeys.list(),
    queryFn: async () => {
      const response = await listAdminStations();
      return response.data;
    },
    enabled: networkTier,
  });

  const { data: trips } = useQuery({
    queryKey: ['dashboard', 'trips', currentStation?.code],
    queryFn: async () => {
      const response = await list1({ stationCode: currentStation!.code, limit: 100, horizonHours: 24 });
      return response.data;
    },
    enabled: stationTier && Boolean(currentStation?.code),
  });

  if (!user) {
    return null;
  }

  const onlineAgents = (stations ?? []).filter((s) => s.agentConnected).length;
  const totalStations = stations?.length ?? 0;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{t('title')}</h1>
        <p className="text-muted">{t('welcome')}</p>
      </div>

      {networkTier && totalStations > 0 ? (
        <section className="rounded-xl border border-border bg-surface p-4">
          <h2 className="mb-3 text-lg font-medium">{t('networkSummary')}</h2>
          <dl className="grid gap-3 text-sm sm:grid-cols-3">
            <div>
              <dt className="text-muted">{t('stationsTotal')}</dt>
              <dd className="text-2xl font-semibold">{totalStations}</dd>
            </div>
            <div>
              <dt className="text-muted">{t('agentsOnline')}</dt>
              <dd className="text-2xl font-semibold">{onlineAgents}</dd>
            </div>
            <div>
              <dt className="text-muted">{t('agentsOffline')}</dt>
              <dd className="text-2xl font-semibold">{totalStations - onlineAgents}</dd>
            </div>
          </dl>
        </section>
      ) : null}

      {stationTier && currentStation ? (
        <section className="rounded-xl border border-border bg-surface p-4">
          <h2 className="mb-3 text-lg font-medium">{t('currentStation')}</h2>
          <p>
            <span className="font-mono text-muted">{currentStation.code}</span>
            <span className="mx-2">—</span>
            {currentStation.name}, {currentStation.city}
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <Chip size="sm" color={agentStatus?.connected ? 'success' : 'default'}>
              {agentStatus?.connected ? t('agentOnline') : t('agentOffline')}
            </Chip>
            <Chip size="sm" variant="secondary">
              {t('tripsCount', { count: trips?.length ?? 0 })}
            </Chip>
          </div>
        </section>
      ) : null}

      <section className="rounded-xl border border-border bg-surface p-4">
        <h2 className="mb-3 text-lg font-medium">{t('profile')}</h2>
        <dl className="grid gap-2 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-muted">{t('fullName')}</dt>
            <dd>{user.fullName}</dd>
          </div>
          <div>
            <dt className="text-muted">{t('login')}</dt>
            <dd className="font-mono">{user.login}</dd>
          </div>
          <div>
            <dt className="text-muted">{t('role')}</dt>
            <dd>{user.isSuperuser ? t('roleSuperuser') : t('roleStationScoped')}</dd>
          </div>
          <div>
            <dt className="text-muted">{t('stationId')}</dt>
            <dd className="font-mono">{user.stationId ?? '—'}</dd>
          </div>
        </dl>
      </section>

      <section className="rounded-xl border border-border bg-surface p-4">
        <h2 className="mb-3 text-lg font-medium">{t('permissions', { count: user.permissions.length })}</h2>
        <div className="flex flex-wrap gap-2">
          {user.permissions.map((permission) => (
            <Chip key={permission} size="sm" variant="secondary" className="font-mono text-xs">
              {permission}
            </Chip>
          ))}
        </div>
      </section>
    </div>
  );
}
