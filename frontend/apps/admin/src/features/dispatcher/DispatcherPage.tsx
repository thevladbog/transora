import { useQuery } from '@tanstack/react-query';
import { Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { list1 } from '@transora/api-client';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { useStationContext } from '@/features/stations/station-context';

export function DispatcherPage() {
  const { t } = useTranslation('dispatcher');
  const { currentStation } = useStationContext();
  const stationCode = currentStation?.code;

  const { data: trips, isLoading, isError } = useQuery({
    queryKey: ['dispatcher', 'trips', stationCode],
    queryFn: async () => {
      const response = await list1({ stationCode, limit: 50, horizonHours: 12 });
      return response.data;
    },
    enabled: Boolean(stationCode),
    refetchInterval: 30_000,
  });

  return (
    <div>
      <PageHeader title={t('title')} description={t('description')} />
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
                <Table.Column isRowHeader>{t('tripId')}</Table.Column>
                <Table.Column>{t('status')}</Table.Column>
                <Table.Column>{t('openedAt')}</Table.Column>
              </Table.Header>
              <Table.Body items={trips ?? []}>
                {(item) => (
                  <Table.Row id={item.id}>
                    <Table.Cell className="font-mono text-sm">{item.routeNumber}</Table.Cell>
                    <Table.Cell className="font-mono">{item.status}</Table.Cell>
                    <Table.Cell className="text-sm text-muted">{item.departureTime}</Table.Cell>
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
