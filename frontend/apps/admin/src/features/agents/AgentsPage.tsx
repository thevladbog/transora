import { Button, Chip } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { useStationContext } from '@/features/stations/station-context';
import { useForceAgentSync, useStationAgentStatus } from '@/features/stations/api/hooks';
import { isNetworkTier } from '@/lib/app-tier';
import { useStationsList } from '@/features/stations/api/hooks';
import { DisplayBoardsTable } from './DisplayBoardsTable';

function StationAgentCard({ stationId, code, name }: { stationId: string; code: string; name: string }) {
  const { t } = useTranslation(['agents', 'common']);
  const { data: status, isLoading, isError } = useStationAgentStatus(stationId, 15_000);
  const forceSync = useForceAgentSync();

  return (
    <section className="rounded-xl border border-border bg-surface p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h3 className="font-medium">
            <span className="font-mono text-muted">{code}</span>
            <span className="mx-2">—</span>
            {name}
          </h3>
          <p className="text-sm text-muted">{t('agents:stationAgentDescription')}</p>
        </div>
        <Chip size="sm" color={status?.connected ? 'success' : 'default'} variant={status?.connected ? 'primary' : 'secondary'}>
          {status?.connected ? t('agents:online') : t('agents:offline')}
        </Chip>
      </div>
      <QueryState isLoading={isLoading} isError={isError} errorMessage={t('agents:loadError')}>
        <dl className="mt-4 grid gap-2 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-muted">{t('agents:lastSeen')}</dt>
            <dd>{status?.lastSeenAt ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-muted">{t('agents:version')}</dt>
            <dd className="font-mono">{status?.agentVersion ?? '—'}</dd>
          </div>
        </dl>
        <div className="mt-4">
          <Button
            variant="secondary"
            size="sm"
            isDisabled={!status?.connected || forceSync.isPending}
            onPress={() => void forceSync.mutateAsync(stationId)}
          >
            {t('agents:syncForce')}
          </Button>
        </div>
      </QueryState>
    </section>
  );
}

export function AgentsPage() {
  const { t } = useTranslation('agents');
  const networkTier = isNetworkTier();
  const { data: allStations, isLoading: listLoading, isError: listError } = useStationsList();
  const { currentStationId, currentStation } = useStationContext();

  if (networkTier) {
    return (
      <div>
        <PageHeader title={t('titleNetwork')} description={t('descriptionNetwork')} />
        <QueryState
          isLoading={listLoading}
          isError={listError}
          isEmpty={!listLoading && !listError && (allStations?.length ?? 0) === 0}
          errorMessage={t('loadError')}
          emptyMessage={t('emptyNetwork')}
        >
          <div className="space-y-4">
            {(allStations ?? []).map((station) => (
              <StationAgentCard key={station.id} stationId={station.id} code={station.code} name={station.name} />
            ))}
          </div>
        </QueryState>
      </div>
    );
  }

  if (!currentStationId || !currentStation) {
    return null;
  }

  return (
    <div>
      <PageHeader title={t('titleStation')} description={t('descriptionStation')} />
      <StationAgentCard
        stationId={currentStationId}
        code={currentStation.code}
        name={currentStation.name}
      />
      <DisplayBoardsTable stationId={currentStationId} />
    </div>
  );
}
