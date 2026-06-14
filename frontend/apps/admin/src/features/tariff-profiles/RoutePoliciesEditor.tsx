import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Chip, ListBox, Select } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { RoutePolicyEntry } from '@transora/api-client';
import { usePoliciesList, useReplaceRoutePolicies, useRoutePolicies } from '@/features/refund-policies/api/hooks';

type RoutePoliciesEditorProps = {
  routeId: string | undefined;
};

export function RoutePoliciesEditor({ routeId }: RoutePoliciesEditorProps) {
  const { t } = useTranslation(['tariffProfiles', 'refundPolicies', 'common']);
  const { data: routePolicies, refetch } = useRoutePolicies(routeId);
  const { data: allPolicies } = usePoliciesList();
  const replacePolicies = useReplaceRoutePolicies(routeId ?? '');
  const [bindings, setBindings] = useState<RoutePolicyEntry[]>([]);
  const [selectedPolicyId, setSelectedPolicyId] = useState('');

  useEffect(() => {
    setBindings(routePolicies?.policies ?? []);
  }, [routePolicies]);

  const policyById = useMemo(
    () => new Map((allPolicies ?? []).map((policy) => [policy.id, policy])),
    [allPolicies],
  );

  const refundCount = useMemo(
    () => bindings.filter((entry) => entry.policyType === 'REFUND').length,
    [bindings],
  );

  const hasDuplicateRefund = refundCount > 1;

  const availablePolicies = useMemo(
    () =>
      (allPolicies ?? []).filter((policy) => {
        if (bindings.some((binding) => binding.policyId === policy.id)) return false;
        if (policy.policyType === 'REFUND' && refundCount >= 1) return false;
        return true;
      }),
    [allPolicies, bindings, refundCount],
  );

  if (!routeId) {
    return (
      <p className="text-sm text-muted">{t('tariffProfiles:routePoliciesNoRoute')}</p>
    );
  }

  function addPolicy() {
    if (!selectedPolicyId) return;
    const policy = allPolicies?.find((p) => p.id === selectedPolicyId);
    if (!policy) return;
    if (policy.policyType === 'REFUND' && refundCount >= 1) return;
    setBindings((prev) => [
      ...prev,
      {
        policyId: policy.id,
        priority: prev.length + 1,
        policyName: policy.name,
        policyType: policy.policyType,
        policyActive: policy.isActive,
      },
    ]);
    setSelectedPolicyId('');
  }

  function move(index: number, direction: -1 | 1) {
    setBindings((prev) => {
      const next = [...prev];
      const target = index + direction;
      if (target < 0 || target >= next.length) return prev;
      [next[index], next[target]] = [next[target], next[index]];
      return next.map((entry, i) => ({ ...entry, priority: i + 1 }));
    });
  }

  function remove(index: number) {
    setBindings((prev) =>
      prev.filter((_, i) => i !== index).map((entry, i) => ({ ...entry, priority: i + 1 })),
    );
  }

  async function save() {
    if (hasDuplicateRefund) return;
    await replacePolicies.mutateAsync(
      bindings.map((entry) => ({ policyId: entry.policyId, priority: entry.priority })),
    );
    await refetch();
  }

  return (
    <section className="space-y-3 rounded-xl border border-border p-4">
      <h2 className="text-base font-semibold">{t('tariffProfiles:routePoliciesSection')}</h2>
      <p className="text-sm text-muted">{t('tariffProfiles:routePoliciesHint')}</p>
      {hasDuplicateRefund ? (
        <Alert status="danger">
          <Alert.Indicator />
          <Alert.Content>
            <Alert.Description>{t('tariffProfiles:routePoliciesDuplicateRefund')}</Alert.Description>
          </Alert.Content>
        </Alert>
      ) : null}
      <div className="flex flex-wrap items-end gap-2">
        <div className="min-w-64 flex-1">
          <Select
            variant="secondary"
            fullWidth
            selectedKey={selectedPolicyId || null}
            onSelectionChange={(key) => setSelectedPolicyId(key ? String(key) : '')}
          >
            <Select.Trigger>
              <Select.Value />
              <Select.Indicator />
            </Select.Trigger>
            <Select.Popover>
              <ListBox>
                {availablePolicies.map((policy) => (
                  <ListBox.Item key={policy.id} id={policy.id} textValue={policy.name}>
                    {policy.name} ({t(`refundPolicies:policyType_${policy.policyType}`)})
                  </ListBox.Item>
                ))}
              </ListBox>
            </Select.Popover>
          </Select>
        </div>
        <Button variant="secondary" onPress={addPolicy} isDisabled={!selectedPolicyId}>
          {t('tariffProfiles:addRoutePolicy')}
        </Button>
        <Button
          variant="primary"
          isPending={replacePolicies.isPending}
          isDisabled={hasDuplicateRefund}
          onPress={() => void save()}
        >
          {t('common:save')}
        </Button>
      </div>
      <ol className="space-y-2">
        {bindings.map((entry, index) => {
          const policy = policyById.get(entry.policyId);
          return (
            <li key={entry.policyId} className="flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
              <div className="flex flex-wrap items-center gap-2">
                <span>
                  {entry.priority}. {entry.policyName}
                </span>
                <Chip size="sm" variant="secondary">
                  {t(`refundPolicies:policyType_${entry.policyType}`)}
                </Chip>
                {entry.policyType === 'SALE' && policy?.isMandatory ? (
                  <Chip size="sm" variant="primary">
                    {t('tariffProfiles:mandatoryBadge')}
                  </Chip>
                ) : null}
                {!entry.policyActive ? (
                  <Chip size="sm" variant="secondary">
                    {t('tariffProfiles:routePoliciesInactive')}
                  </Chip>
                ) : null}
              </div>
              <div className="flex shrink-0 gap-2">
                <Button size="sm" variant="secondary" onPress={() => move(index, -1)}>↑</Button>
                <Button size="sm" variant="secondary" onPress={() => move(index, 1)}>↓</Button>
                <Button size="sm" variant="secondary" onPress={() => remove(index)}>{t('common:delete')}</Button>
              </div>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
