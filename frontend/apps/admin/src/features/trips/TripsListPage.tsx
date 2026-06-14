import { useState } from 'react';
import { Button, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { PermissionGate } from '@/components/layout/PermissionGate';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { useStationContext } from '@/features/stations/station-context';
import { Permissions } from '@/lib/permissions';
import { useTripsList } from './api/hooks';
import { CreateTripFromRouteDrawer } from './CreateTripFromRouteDrawer';
import { TripDetailDrawer } from './TripDetailDrawer';

export function TripsListPage() {
  const { t } = useTranslation(['trips', 'common']);
  const { currentStation } = useStationContext();
  const stationCode = currentStation?.code;
  const [createTripOpen, setCreateTripOpen] = useState(false);
  const [detailTripId, setDetailTripId] = useState<string | null>(null);
  const [detailOpen, setDetailOpen] = useState(false);

  const { data: trips, isLoading, isError, refetch } = useTripsList(stationCode);

  function openTripDetail(tripId: string) {
    setDetailTripId(tripId);
    setDetailOpen(true);
  }

  function handleDetailOpenChange(open: boolean) {
    setDetailOpen(open);
    if (!open) {
      setDetailTripId(null);
    }
  }

  return (
    <div>
      <PageHeader
        title={t('trips:title')}
        description={t('trips:description')}
        actions={
          <PermissionGate permission={Permissions.SCHEDULE_CREATE}>
            <Button variant="primary" onPress={() => setCreateTripOpen(true)}>
              {t('trips:createTrip')}
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

      <TripDetailDrawer
        tripId={detailTripId}
        isOpen={detailOpen}
        onOpenChange={handleDetailOpenChange}
        stationCode={stationCode}
        onSuccess={() => void refetch()}
      />

      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (trips?.length ?? 0) === 0}
        errorMessage={t('trips:loadError')}
        emptyMessage={t('trips:empty')}
      >
        <Table aria-label={t('trips:title')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('trips:tripNumber')}</Table.Column>
                <Table.Column>{t('trips:route')}</Table.Column>
                <Table.Column>{t('trips:departure')}</Table.Column>
                <Table.Column>{t('trips:status')}</Table.Column>
                <Table.Column>{t('trips:platform')}</Table.Column>
                <Table.Column>{t('common:actions')}</Table.Column>
              </Table.Header>
              <Table.Body items={trips ?? []}>
                {(item) => (
                  <Table.Row id={item.id}>
                    <Table.Cell>{item.tripNumber ?? item.routeNumber}</Table.Cell>
                    <Table.Cell>{item.routeNumber}</Table.Cell>
                    <Table.Cell className="font-mono text-sm">{item.departureTime}</Table.Cell>
                    <Table.Cell className="font-mono">{item.status}</Table.Cell>
                    <Table.Cell>{item.platform ?? '—'}</Table.Cell>
                    <Table.Cell>
                      <Button size="sm" variant="secondary" onPress={() => openTripDetail(item.id)}>
                        {t('trips:openTrip')}
                      </Button>
                    </Table.Cell>
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
