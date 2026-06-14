import { useState } from 'react';
import { Button, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { NomenclatureResponse } from '@transora/api-client';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { StatusChip } from '@/components/ui/StatusChip';
import { formatMoney } from '@/lib/format';
import { useDeleteNomenclature, useNomenclatureList } from './api/hooks';
import { NomenclatureFormDrawer } from './NomenclatureFormDrawer';

function truncate(text: string, max = 24) {
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

export function NomenclatureListPage() {
  const { t, i18n } = useTranslation(['nomenclature', 'common']);
  const { data: items, isLoading, isError } = useNomenclatureList();
  const deleteItem = useDeleteNomenclature();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<NomenclatureResponse | null>(null);
  const [deleting, setDeleting] = useState<NomenclatureResponse | null>(null);

  return (
    <div>
      <PageHeader
        title={t('nomenclature:title')}
        description={t('nomenclature:description')}
        actions={
          <Button variant="primary" onPress={() => { setEditing(null); setDrawerOpen(true); }}>
            {t('nomenclature:create')}
          </Button>
        }
      />
      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (items?.length ?? 0) === 0}
        errorMessage={t('nomenclature:loadError')}
        emptyMessage={t('nomenclature:empty')}
      >
        <Table aria-label={t('nomenclature:title')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('nomenclature:code')}</Table.Column>
                <Table.Column>{t('nomenclature:name')}</Table.Column>
                <Table.Column>{t('nomenclature:category')}</Table.Column>
                <Table.Column>{t('nomenclature:saleMode')}</Table.Column>
                <Table.Column>{t('nomenclature:price')}</Table.Column>
                <Table.Column>{t('nomenclature:refundAllowed')}</Table.Column>
                <Table.Column>{t('nomenclature:printName')}</Table.Column>
                <Table.Column>{t('nomenclature:status')}</Table.Column>
                <Table.Column>{t('common:actions')}</Table.Column>
              </Table.Header>
              <Table.Body items={items ?? []}>
                {(item: NomenclatureResponse) => (
                  <Table.Row id={item.id}>
                    <Table.Cell className="font-mono">{item.code}</Table.Cell>
                    <Table.Cell>{item.name}</Table.Cell>
                    <Table.Cell>{t(`nomenclature:categories.${item.category}`)}</Table.Cell>
                    <Table.Cell>{t(`nomenclature:saleModes.${item.saleMode}`)}</Table.Cell>
                    <Table.Cell className="font-mono">
                      {item.pricingMode === 'PERCENT_OF_ROUTE'
                        ? t('nomenclature:percentPrice', { percent: item.routePercent ?? 0 })
                        : formatMoney(item.priceCents, i18n.language)}
                    </Table.Cell>
                    <Table.Cell>{item.refundAllowed ? t('nomenclature:refundAllowedYes') : t('nomenclature:refundAllowedNo')}</Table.Cell>
                    <Table.Cell className="max-w-[12rem] truncate">{truncate(item.printName)}</Table.Cell>
                    <Table.Cell><StatusChip active={item.isActive} /></Table.Cell>
                    <Table.Cell>
                      <div className="flex gap-2">
                        <Button size="sm" variant="secondary" onPress={() => { setEditing(item); setDrawerOpen(true); }}>
                          {t('nomenclature:edit')}
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
      <NomenclatureFormDrawer isOpen={drawerOpen} item={editing} onOpenChange={(open) => { setDrawerOpen(open); if (!open) setEditing(null); }} />
      <ConfirmDialog
        isOpen={deleting !== null}
        title={t('common:confirm')}
        message={deleting ? t('nomenclature:deleteConfirm', { name: deleting.name }) : ''}
        confirmLabel={t('common:delete')}
        isPending={deleteItem.isPending}
        onConfirm={() => { if (deleting) void deleteItem.mutateAsync(deleting.id).then(() => setDeleting(null)); }}
        onOpenChange={(open) => { if (!open) setDeleting(null); }}
      />
    </div>
  );
}
