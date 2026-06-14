import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Button, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { list1 } from '@transora/api-client';
import { PermissionGate } from '@/components/layout/PermissionGate';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { useStationContext } from '@/features/stations/station-context';
import { Permissions } from '@/lib/permissions';
import { CreateTripFromRouteDrawer } from './CreateTripFromRouteDrawer';

export function TripsListPage() {
  const { t } = useTranslation('trips');
  const { currentStation } = useStationContext();
  const stationCode = currentStation?.code;
  const [createTripOpen, setCreateTripOpen] = useState(false);

  const { data: trips, isLoading, isError, refetch } = useQuery({
    queryKey: ['trips', stationCode],
    queryFn: async () => {
      const response = await list1({ stationCode, limit: 100, horizonHours: 24 });
      return response.data;
    },
    enabled: Boolean(stationCode),
  });

  return (
    <div>
      <PageHeader
        title={t('title')}
        description={t('description')}
        actions={
          <PermissionGate permission={Permissions.SCHEDULE_CREATE}>
            <Button variant="primary" onPress={() => setCreateTripOpen(true)}>
              {t('createTrip')}
            </Button>
          </PermissionGate>
        }
      />

      <CreateTripFromRouteDrawer
        isOpen={createTripOpen}
        onOpenChange={setCreateTripOpen}
        stationCode={stationCode}
        onSuccess={() => void refetch()}
      />

      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (trips?.length ?? 0) === 0}
        errorMessage={t('loadError')}
        emptyMessage={t('empty')}
      >
        <Table aria-label={t('title')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('route')}</Table.Column>
                <Table.Column>{t('departure')}</Table.Column>
                <Table.Column>{t('status')}</Table.Column>
                <Table.Column>{t('platform')}</Table.Column>
              </Table.Header>
              <Table.Body items={trips ?? []}>
                {(item) => (
                  <Table.Row id={item.id}>
                    <Table.Cell>{item.routeNumber}</Table.Cell>
                    <Table.Cell className="font-mono text-sm">{item.departureTime}</Table.Cell>
                    <Table.Cell className="font-mono">{item.status}</Table.Cell>
                    <Table.Cell>{item.platform ?? '—'}</Table.Cell>
                  </Table.Row>
                )}
              </Table.Body>
            </Table.Content>
          </Table.ScrollContainer>
        </Table>
      </QueryState>
    </div>
  );
}
