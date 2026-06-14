import { useMemo, useState } from 'react';
import { Button, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { CreateScheduleRequestScheduleType } from '@transora/api-client';
import { PermissionGate } from '@/components/layout/PermissionGate';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { StatusChip } from '@/components/ui/StatusChip';
import { Permissions } from '@/lib/permissions';
import { useRoutesPricingList } from '@/features/routes/api/hooks';
import { formatRouteLabel } from '@/features/routes/route-label';
import { useSchedulesList } from './api/hooks';
import { GenerateTripsDialog } from './GenerateTripsDialog';

function formatPeriod(
  scheduleType: string,
  validFrom: string | undefined,
  validTo: string | undefined,
  t: (key: string) => string,
): string {
  if (scheduleType === CreateScheduleRequestScheduleType.PERMANENT) {
    return t('periodPermanent');
  }
  if (scheduleType === CreateScheduleRequestScheduleType.EXCEPTION) {
    return validFrom ?? '—';
  }
  if (validFrom && validTo) {
    return `${validFrom} — ${validTo}`;
  }
  return '—';
}

export function SchedulesListPage() {
  const { t } = useTranslation(['schedules', 'common']);
  const navigate = useNavigate();
  const { data: schedules, isLoading, isError } = useSchedulesList();
  const { data: routes } = useRoutesPricingList();
  const [generateOpen, setGenerateOpen] = useState(false);

  const routeLabelById = useMemo(() => {
    const map = new Map<string, string>();
    for (const route of routes ?? []) {
      map.set(route.routeId, formatRouteLabel(route));
    }
    return map;
  }, [routes]);

  return (
    <div>
      <PageHeader
        title={t('schedules:title')}
        description={t('schedules:description')}
        actions={
          <div className="flex flex-wrap gap-2">
            <PermissionGate permission={Permissions.SCHEDULE_CREATE}>
              <Button variant="secondary" onPress={() => setGenerateOpen(true)}>
                {t('schedules:generateTrips')}
              </Button>
            </PermissionGate>
            <PermissionGate permission={Permissions.SCHEDULE_CREATE}>
              <Button variant="primary" onPress={() => navigate('/schedules/new')}>
                {t('schedules:create')}
              </Button>
            </PermissionGate>
          </div>
        }
      />

      <GenerateTripsDialog isOpen={generateOpen} onOpenChange={setGenerateOpen} />

      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (schedules?.length ?? 0) === 0}
        errorMessage={t('schedules:loadError')}
        emptyMessage={t('schedules:empty')}
      >
        <Table aria-label={t('schedules:title')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('schedules:name')}</Table.Column>
                <Table.Column>{t('schedules:route')}</Table.Column>
                <Table.Column>{t('schedules:scheduleType')}</Table.Column>
                <Table.Column>{t('schedules:period')}</Table.Column>
                <Table.Column>{t('schedules:status')}</Table.Column>
                <Table.Column>{t('common:actions')}</Table.Column>
              </Table.Header>
              <Table.Body items={schedules ?? []}>
                {(item) => (
                  <Table.Row id={item.id}>
                    <Table.Cell>{item.name}</Table.Cell>
                    <Table.Cell>{routeLabelById.get(item.routeId) ?? item.routeId}</Table.Cell>
                    <Table.Cell>{t(`schedules:type.${item.scheduleType}`)}</Table.Cell>
                    <Table.Cell className="font-mono text-sm">
                      {formatPeriod(item.scheduleType, item.validFrom, item.validTo, (key) => t(`schedules:${key}`))}
                    </Table.Cell>
                    <Table.Cell>
                      <StatusChip active={item.isActive} />
                    </Table.Cell>
                    <Table.Cell>
                      <PermissionGate permission={Permissions.SCHEDULE_EDIT}>
                        <Button size="sm" variant="primary" onPress={() => navigate(`/schedules/${item.id}`)}>
                          {t('schedules:edit')}
                        </Button>
                      </PermissionGate>
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
