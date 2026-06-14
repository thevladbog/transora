import { useMemo } from 'react';
import { Button, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { PermissionGate } from '@/components/layout/PermissionGate';
import { QueryState } from '@/components/ui/QueryState';
import { StatusChip } from '@/components/ui/StatusChip';
import { Permissions } from '@/lib/permissions';
import { formatSchedulePeriod } from '@/features/schedules/schedule-format';
import { useSchedulesList } from '@/features/schedules/api/hooks';

type RouteSchedulesSectionProps = {
  routeId: string;
};

export function RouteSchedulesSection({ routeId }: RouteSchedulesSectionProps) {
  const { t } = useTranslation(['routes', 'schedules', 'common']);
  const navigate = useNavigate();
  const { data: schedules, isLoading, isError } = useSchedulesList();

  const routeSchedules = useMemo(
    () => (schedules ?? []).filter((schedule) => schedule.routeId === routeId),
    [schedules, routeId],
  );

  return (
    <section className="space-y-4 rounded-xl border border-border p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-base font-semibold">{t('routes:schedulesSection')}</h2>
        <div className="flex flex-wrap gap-2">
          <Link
            to="/schedules"
            className="inline-flex items-center rounded-lg border border-border px-3 py-2 text-sm hover:bg-default"
          >
            {t('routes:viewAllSchedules')}
          </Link>
          <PermissionGate permission={Permissions.SCHEDULE_CREATE}>
            <Button variant="primary" onPress={() => navigate(`/schedules/new?routeId=${routeId}`)}>
              {t('routes:createSchedule')}
            </Button>
          </PermissionGate>
        </div>
      </div>

      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && routeSchedules.length === 0}
        errorMessage={t('schedules:loadError')}
        emptyMessage={t('routes:schedulesEmpty')}
      >
        <Table aria-label={t('routes:schedulesSection')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('schedules:name')}</Table.Column>
                <Table.Column>{t('schedules:scheduleType')}</Table.Column>
                <Table.Column>{t('schedules:period')}</Table.Column>
                <Table.Column>{t('schedules:status')}</Table.Column>
                <Table.Column>{t('common:actions')}</Table.Column>
              </Table.Header>
              <Table.Body items={routeSchedules}>
                {(item) => (
                  <Table.Row id={item.id}>
                    <Table.Cell>{item.name}</Table.Cell>
                    <Table.Cell>{t(`schedules:type.${item.scheduleType}`)}</Table.Cell>
                    <Table.Cell className="font-mono text-sm">
                      {formatSchedulePeriod(item.scheduleType, item.validFrom, item.validTo, (key) =>
                        t(`schedules:${key}`),
                      )}
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
    </section>
  );
}
