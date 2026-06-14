import { useMemo, useState } from 'react';
import { Button, Label, ListBox, Select, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { CommercePolicyResponse } from '@transora/api-client';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { StatusChip } from '@/components/ui/StatusChip';
import { formatPolicyMandatory, formatPolicyPrice } from '@/lib/policy-format';
import { useDeletePolicy, usePoliciesList } from './api/hooks';
import { PolicyFormDrawer } from './RefundPolicyFormDrawer';

type PolicyTypeFilter = 'ALL' | 'REFUND' | 'SALE';

export function RefundPoliciesListPage() {
  const { t, i18n } = useTranslation(['refundPolicies', 'common']);
  const [typeFilter, setTypeFilter] = useState<PolicyTypeFilter>('ALL');
  const policyTypeParam = typeFilter === 'ALL' ? undefined : typeFilter;
  const { data: policies, isLoading, isError } = usePoliciesList(policyTypeParam);
  const deletePolicy = useDeletePolicy();

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<CommercePolicyResponse | null>(null);
  const [deleting, setDeleting] = useState<CommercePolicyResponse | null>(null);

  const filterOptions = useMemo(
    () =>
      [
        { id: 'ALL', label: t('refundPolicies:filterAll') },
        { id: 'REFUND', label: t('refundPolicies:filterRefund') },
        { id: 'SALE', label: t('refundPolicies:filterSale') },
      ] as const,
    [t],
  );

  function openCreate() {
    setEditing(null);
    setFormOpen(true);
  }

  function openEdit(policy: CommercePolicyResponse) {
    setEditing(policy);
    setFormOpen(true);
  }

  return (
    <div>
      <PageHeader
        title={t('refundPolicies:title')}
        description={t('refundPolicies:description')}
        actions={
          <Button variant="primary" onPress={openCreate}>
            {t('refundPolicies:create')}
          </Button>
        }
      />

      <div className="mb-4 max-w-xs space-y-1">
        <Label>{t('refundPolicies:policyType')}</Label>
        <Select
          variant="secondary"
          fullWidth
          selectedKey={typeFilter}
          onSelectionChange={(key) => setTypeFilter((key ? String(key) : 'ALL') as PolicyTypeFilter)}
        >
          <Select.Trigger>
            <Select.Value />
            <Select.Indicator />
          </Select.Trigger>
          <Select.Popover>
            <ListBox>
              {filterOptions.map((option) => (
                <ListBox.Item key={option.id} id={option.id} textValue={option.label}>
                  {option.label}
                </ListBox.Item>
              ))}
            </ListBox>
          </Select.Popover>
        </Select>
      </div>

      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (policies?.length ?? 0) === 0}
        errorMessage={t('refundPolicies:loadError')}
        emptyMessage={t('refundPolicies:empty')}
      >
        <Table aria-label={t('refundPolicies:title')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('refundPolicies:name')}</Table.Column>
                <Table.Column>{t('refundPolicies:policyType')}</Table.Column>
                <Table.Column>{t('refundPolicies:nomenclature')}</Table.Column>
                <Table.Column>{t('refundPolicies:mandatory')}</Table.Column>
                <Table.Column>{t('refundPolicies:pricingMode')}</Table.Column>
                <Table.Column>{t('refundPolicies:priceSummary')}</Table.Column>
                <Table.Column>{t('refundPolicies:thresholdsCount')}</Table.Column>
                <Table.Column>{t('refundPolicies:status')}</Table.Column>
                <Table.Column>{t('common:actions')}</Table.Column>
              </Table.Header>
              <Table.Body items={policies ?? []}>
                {(item: CommercePolicyResponse) => (
                  <Table.Row id={item.id}>
                    <Table.Cell>{item.name}</Table.Cell>
                    <Table.Cell>{t(`refundPolicies:policyType_${item.policyType}`)}</Table.Cell>
                    <Table.Cell>{item.nomenclatureName ?? '—'}</Table.Cell>
                    <Table.Cell>{formatPolicyMandatory(item, t)}</Table.Cell>
                    <Table.Cell>{t(`refundPolicies:pricingMode_${item.pricingMode}`)}</Table.Cell>
                    <Table.Cell className="font-mono text-sm">
                      {formatPolicyPrice(item, i18n.language, t)}
                    </Table.Cell>
                    <Table.Cell className="font-mono">{item.tiers.length}</Table.Cell>
                    <Table.Cell>
                      <StatusChip active={item.isActive} />
                    </Table.Cell>
                    <Table.Cell>
                      <div className="flex flex-wrap gap-2">
                        <Button size="sm" variant="secondary" onPress={() => openEdit(item)}>
                          {t('refundPolicies:edit')}
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

      <PolicyFormDrawer
        isOpen={formOpen}
        policy={editing}
        onOpenChange={(open) => {
          setFormOpen(open);
          if (!open) {
            setEditing(null);
          }
        }}
      />

      <ConfirmDialog
        isOpen={deleting !== null}
        title={t('common:confirm')}
        message={deleting ? t('refundPolicies:deleteConfirm', { name: deleting.name }) : ''}
        confirmLabel={t('common:delete')}
        isPending={deletePolicy.isPending}
        onConfirm={() => {
          if (deleting) {
            void deletePolicy.mutateAsync(deleting.id).then(() => setDeleting(null));
          }
        }}
        onOpenChange={(open) => {
          if (!open) {
            setDeleting(null);
          }
        }}
      />
    </div>
  );
}
