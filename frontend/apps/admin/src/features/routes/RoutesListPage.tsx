import { Button, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { StatusChip } from '@/components/ui/StatusChip';
import { useRoutesPricingList } from './api/hooks';

export function RoutesListPage() {
  const { t } = useTranslation(['routes', 'common']);
  const navigate = useNavigate();
  const { data: routes, isLoading, isError } = useRoutesPricingList();

  return (
    <div>
      <PageHeader
        title={t('routes:title')}
        description={t('routes:description')}
        actions={
          <Button variant="primary" onPress={() => navigate('/routes/new')}>
            {t('routes:create')}
          </Button>
        }
      />
      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (routes?.length ?? 0) === 0}
        errorMessage={t('routes:loadError')}
        emptyMessage={t('routes:empty')}
      >
        <Table aria-label={t('routes:title')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('routes:internalCode')}</Table.Column>
                <Table.Column>{t('routes:routeName')}</Table.Column>
                <Table.Column>{t('routes:carrier')}</Table.Column>
                <Table.Column>{t('routes:stopsCount')}</Table.Column>
                <Table.Column>{t('routes:matrixCells')}</Table.Column>
                <Table.Column>{t('routes:status')}</Table.Column>
                <Table.Column>{t('common:actions')}</Table.Column>
              </Table.Header>
              <Table.Body items={routes ?? []}>
                {(item) => (
                  <Table.Row id={item.routeId}>
                    <Table.Cell className="font-mono">{item.code ?? '—'}</Table.Cell>
                    <Table.Cell>{item.name}</Table.Cell>
                    <Table.Cell>{item.carrierName ?? '—'}</Table.Cell>
                    <Table.Cell className="font-mono">{item.stopCount}</Table.Cell>
                    <Table.Cell className="font-mono">{item.matrixCellCount}</Table.Cell>
                    <Table.Cell><StatusChip active={item.isActive} /></Table.Cell>
                    <Table.Cell>
                      <Button size="sm" variant="primary" onPress={() => navigate(`/routes/${item.routeId}`)}>
                        {t('routes:edit')}
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
