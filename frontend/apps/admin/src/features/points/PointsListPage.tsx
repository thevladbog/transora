import { useState } from 'react';
import { Button, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { PointResponse } from '@transora/api-client';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { StatusChip } from '@/components/ui/StatusChip';
import { useDeletePoint, usePointsList } from './api/hooks';
import { PointFormDrawer } from './PointFormDrawer';

export function PointsListPage() {
  const { t } = useTranslation(['points', 'common']);
  const { data: points, isLoading, isError } = usePointsList();
  const deletePoint = useDeletePoint();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<PointResponse | null>(null);
  const [deleting, setDeleting] = useState<PointResponse | null>(null);

  return (
    <div>
      <PageHeader
        title={t('points:title')}
        description={t('points:description')}
        actions={
          <Button variant="primary" onPress={() => { setEditing(null); setDrawerOpen(true); }}>
            {t('points:create')}
          </Button>
        }
      />
      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (points?.length ?? 0) === 0}
        errorMessage={t('points:loadError')}
        emptyMessage={t('points:empty')}
      >
        <Table aria-label={t('points:title')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('points:code')}</Table.Column>
                <Table.Column>{t('points:name')}</Table.Column>
                <Table.Column>{t('points:city')}</Table.Column>
                <Table.Column>{t('points:coordinates')}</Table.Column>
                <Table.Column>{t('points:status')}</Table.Column>
                <Table.Column>{t('common:actions')}</Table.Column>
              </Table.Header>
              <Table.Body items={points ?? []}>
                {(item: PointResponse) => (
                  <Table.Row id={item.id}>
                    <Table.Cell className="font-mono">{item.code}</Table.Cell>
                    <Table.Cell>{item.name}</Table.Cell>
                    <Table.Cell>{item.city}</Table.Cell>
                    <Table.Cell className="font-mono text-sm">
                      {item.latitude.toFixed(4)}, {item.longitude.toFixed(4)}
                    </Table.Cell>
                    <Table.Cell>
                      <StatusChip active={item.isActive} />
                    </Table.Cell>
                    <Table.Cell>
                      <div className="flex gap-2">
                        <Button size="sm" variant="secondary" onPress={() => { setEditing(item); setDrawerOpen(true); }}>
                          {t('points:edit')}
                        </Button>
                        <Button size="sm" variant="secondary" onPress={() => setDeleting(item)}>
                          {t('common:delete')}
                        </Button>
                      </div>
                    </Table.Cell>
                  </Table.Row>
                )}
              </Table.Body>
            </Table.Content>
          </Table.ScrollContainer>
        </Table>
      </QueryState>
      <PointFormDrawer isOpen={drawerOpen} point={editing} onOpenChange={(open) => { setDrawerOpen(open); if (!open) setEditing(null); }} />
      <ConfirmDialog
        isOpen={deleting !== null}
        title={t('common:confirm')}
        message={deleting ? t('points:deleteConfirm', { name: deleting.name }) : ''}
        confirmLabel={t('common:delete')}
        isPending={deletePoint.isPending}
        onConfirm={() => { if (deleting) void deletePoint.mutateAsync(deleting.id).then(() => setDeleting(null)); }}
        onOpenChange={(open) => { if (!open) setDeleting(null); }}
      />
    </div>
  );
}
