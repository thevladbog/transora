import { useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Button, Label, ListBox, Switch } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';
import type { CommercePolicyResponse, CommercePolicyTierRequest } from '@transora/api-client';
import { FormDrawer } from '@/components/ui/FormDrawer';
import { FormSelectField, FormTextField } from '@/components/ui/FormFields';
import { useNomenclatureList } from '@/features/nomenclature/api/hooks';
import { useCreatePolicy, useUpdatePolicy } from './api/hooks';

type TierRow = {
  key: string;
  hoursBeforeMin: string;
  hoursBeforeMax: string;
  penaltyPercent: string;
  refundAllowed: boolean;
  fixedPriceCents: string;
  percentValue: string;
};

const POLICY_TYPES = ['REFUND', 'SALE'] as const;
const PRICING_MODES = ['FROM_NOMENCLATURE', 'FIXED', 'PERCENT'] as const;
const PERCENT_BASES = ['ROUTE_PRICE', 'REFUND_AMOUNT'] as const;

function emptyTier(): TierRow {
  return {
    key: crypto.randomUUID(),
    hoursBeforeMin: '',
    hoursBeforeMax: '',
    penaltyPercent: '0',
    refundAllowed: true,
    fixedPriceCents: '',
    percentValue: '',
  };
}

function tiersFromPolicy(policy: CommercePolicyResponse): TierRow[] {
  if (policy.tiers.length === 0) return [emptyTier()];
  return policy.tiers.map((tier) => ({
    key: tier.id,
    hoursBeforeMin: tier.hoursBeforeMin != null ? String(tier.hoursBeforeMin) : '',
    hoursBeforeMax: tier.hoursBeforeMax != null ? String(tier.hoursBeforeMax) : '',
    penaltyPercent: String(tier.penaltyPercent),
    refundAllowed: tier.refundAllowed,
    fixedPriceCents: tier.fixedPriceCents != null ? String(tier.fixedPriceCents) : '',
    percentValue: tier.percentValue != null ? String(tier.percentValue) : '',
  }));
}

function tiersToPayload(rows: TierRow[], policyType: string): CommercePolicyTierRequest[] {
  return rows.map((row, index) => ({
    hoursBeforeMin: row.hoursBeforeMin ? Number(row.hoursBeforeMin) : undefined,
    hoursBeforeMax: row.hoursBeforeMax ? Number(row.hoursBeforeMax) : undefined,
    penaltyPercent: policyType === 'REFUND' ? Number(row.penaltyPercent) : 0,
    refundAllowed: policyType === 'REFUND' ? row.refundAllowed : true,
    sortOrder: index,
    fixedPriceCents: row.fixedPriceCents ? Number(row.fixedPriceCents) : undefined,
    percentValue: row.percentValue ? Number(row.percentValue) : undefined,
  }));
}

function intervalsOverlap(a: TierRow, b: TierRow): boolean {
  const aMin = a.hoursBeforeMin ? Number(a.hoursBeforeMin) : Number.NEGATIVE_INFINITY;
  const aMax = a.hoursBeforeMax ? Number(a.hoursBeforeMax) : Number.POSITIVE_INFINITY;
  const bMin = b.hoursBeforeMin ? Number(b.hoursBeforeMin) : Number.NEGATIVE_INFINITY;
  const bMax = b.hoursBeforeMax ? Number(b.hoursBeforeMax) : Number.POSITIVE_INFINITY;
  return aMin < bMax && bMin < aMax;
}

type PolicyFormDrawerProps = {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  policy?: CommercePolicyResponse | null;
};

export function PolicyFormDrawer({ isOpen, onOpenChange, policy }: PolicyFormDrawerProps) {
  return <RefundPolicyFormDrawer isOpen={isOpen} onOpenChange={onOpenChange} policy={policy} />;
}

export function RefundPolicyFormDrawer({ isOpen, onOpenChange, policy }: PolicyFormDrawerProps) {
  const { t } = useTranslation(['refundPolicies', 'common']);
  const isEdit = Boolean(policy);
  const createPolicy = useCreatePolicy();
  const updatePolicy = useUpdatePolicy();
  const { data: nomenclature } = useNomenclatureList();

  const [name, setName] = useState('');
  const [policyType, setPolicyType] = useState<string>('REFUND');
  const [serviceFeeCents, setServiceFeeCents] = useState('0');
  const [isActive, setIsActive] = useState(true);
  const [nomenclatureItemId, setNomenclatureItemId] = useState('');
  const [isMandatory, setIsMandatory] = useState(false);
  const [pricingMode, setPricingMode] = useState<string>('FROM_NOMENCLATURE');
  const [fixedPriceCents, setFixedPriceCents] = useState('');
  const [percentValue, setPercentValue] = useState('');
  const [percentBasis, setPercentBasis] = useState('ROUTE_PRICE');
  const [minPriceCents, setMinPriceCents] = useState('');
  const [maxPriceCents, setMaxPriceCents] = useState('');
  const [tiers, setTiers] = useState<TierRow[]>([emptyTier()]);
  const [error, setError] = useState<string | null>(null);
  const percentBasisTouched = useRef(false);

  const overlapKeys = useMemo(() => {
    const keys = new Set<string>();
    for (let i = 0; i < tiers.length; i += 1) {
      for (let j = i + 1; j < tiers.length; j += 1) {
        if (intervalsOverlap(tiers[i], tiers[j])) {
          keys.add(tiers[i].key);
          keys.add(tiers[j].key);
        }
      }
    }
    return keys;
  }, [tiers]);

  useEffect(() => {
    if (!isOpen) return;
    if (policy) {
      setName(policy.name);
      setPolicyType(policy.policyType);
      setServiceFeeCents(String(policy.serviceFeeCents));
      setIsActive(policy.isActive);
      setNomenclatureItemId(policy.nomenclatureItemId ?? '');
      setIsMandatory(policy.isMandatory);
      setPricingMode(policy.pricingMode);
      setFixedPriceCents(policy.fixedPriceCents != null ? String(policy.fixedPriceCents) : '');
      setPercentValue(policy.percentValue != null ? String(policy.percentValue) : '');
      setPercentBasis(policy.percentBasis ?? (policy.policyType === 'REFUND' ? 'REFUND_AMOUNT' : 'ROUTE_PRICE'));
      setMinPriceCents(policy.minPriceCents != null ? String(policy.minPriceCents) : '');
      setMaxPriceCents(policy.maxPriceCents != null ? String(policy.maxPriceCents) : '');
      setTiers(tiersFromPolicy(policy));
    } else {
      setName('');
      setPolicyType('REFUND');
      setServiceFeeCents('0');
      setIsActive(true);
      setNomenclatureItemId('');
      setIsMandatory(false);
      setPricingMode('FROM_NOMENCLATURE');
      setFixedPriceCents('');
      setPercentValue('');
      setPercentBasis('REFUND_AMOUNT');
      setMinPriceCents('');
      setMaxPriceCents('');
      setTiers([emptyTier()]);
    }
    percentBasisTouched.current = false;
    setError(null);
  }, [isOpen, policy]);

  function handlePolicyTypeChange(key: React.Key | null) {
    const next = key ? String(key) : 'REFUND';
    setPolicyType(next);
    if (!percentBasisTouched.current) {
      setPercentBasis(next === 'REFUND' ? 'REFUND_AMOUNT' : 'ROUTE_PRICE');
    }
  }

  function updateTier(key: string, patch: Partial<TierRow>) {
    setTiers((rows) => rows.map((row) => (row.key === key ? { ...row, ...patch } : row)));
  }

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    if (overlapKeys.size > 0) {
      setError(t('refundPolicies:thresholdsOverlap'));
      return;
    }
    const schema = z.object({
      name: z.string().min(1, t('refundPolicies:nameRequired')),
      serviceFeeCents: z.coerce.number().min(0),
      isActive: z.boolean(),
    });
    const parsed = schema.safeParse({ name, serviceFeeCents, isActive });
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? t('common:errorGeneric'));
      return;
    }
    if (policyType === 'REFUND' && tiers.length < 1) {
      setError(t('refundPolicies:thresholdsRequired'));
      return;
    }
    if (policyType === 'SALE' && !nomenclatureItemId) {
      setError(t('refundPolicies:nomenclatureRequired'));
      return;
    }
    const payload = {
      name: parsed.data.name,
      serviceFeeCents: parsed.data.serviceFeeCents,
      isActive: parsed.data.isActive,
      policyType,
      nomenclatureItemId: nomenclatureItemId || undefined,
      isMandatory: policyType === 'SALE' ? isMandatory : false,
      pricingMode,
      fixedPriceCents: fixedPriceCents ? Number(fixedPriceCents) : undefined,
      percentValue: percentValue ? Number(percentValue) : undefined,
      percentBasis: pricingMode === 'PERCENT' ? percentBasis : undefined,
      minPriceCents: minPriceCents ? Number(minPriceCents) : undefined,
      maxPriceCents: maxPriceCents ? Number(maxPriceCents) : undefined,
      tiers: tiersToPayload(tiers, policyType),
    };
    try {
      if (isEdit && policy) {
        await updatePolicy.mutateAsync({ id: policy.id, payload });
      } else {
        await createPolicy.mutateAsync(payload);
      }
      onOpenChange(false);
    } catch {
      setError(t('refundPolicies:saveError'));
    }
  }

  return (
    <FormDrawer
      isOpen={isOpen}
      onOpenChange={onOpenChange}
      title={isEdit ? t('refundPolicies:editTitle') : t('refundPolicies:createTitle')}
      description={!isEdit ? t('refundPolicies:createDescription') : undefined}
      formId="policy-form"
      size="xl"
      isPending={createPolicy.isPending || updatePolicy.isPending}
    >
      <form className="transora-form-stack" id="policy-form" onSubmit={handleSubmit}>
        <FormTextField isRequired label={t('refundPolicies:name')} name="name" value={name} onChange={setName} />
        <FormSelectField
          label={t('refundPolicies:policyType')}
          name="policyType"
          selectedKey={policyType}
          onSelectionChange={handlePolicyTypeChange}
        >
          {POLICY_TYPES.map((type) => (
            <ListBox.Item key={type} id={type} textValue={t(`refundPolicies:policyType_${type}`)}>
              {t(`refundPolicies:policyType_${type}`)}
            </ListBox.Item>
          ))}
        </FormSelectField>
        <FormSelectField
          label={t('refundPolicies:nomenclature')}
          name="nomenclatureItemId"
          selectedKey={nomenclatureItemId || null}
          onSelectionChange={(key) => setNomenclatureItemId(key ? String(key) : '')}
        >
          <ListBox.Item id="" textValue={t('refundPolicies:noNomenclature')}>
            {t('refundPolicies:noNomenclature')}
          </ListBox.Item>
          {(nomenclature ?? []).map((item) => (
            <ListBox.Item key={item.id} id={item.id} textValue={`${item.code} — ${item.name}`}>
              {item.code} — {item.name}
            </ListBox.Item>
          ))}
        </FormSelectField>
        {policyType === 'SALE' ? (
          <div className="transora-form-field flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
            <Label>{t('refundPolicies:isMandatory')}</Label>
            <Switch isSelected={isMandatory} onChange={setIsMandatory}>
              <Switch.Control><Switch.Thumb /></Switch.Control>
            </Switch>
          </div>
        ) : null}
        <FormSelectField
          label={t('refundPolicies:pricingMode')}
          name="pricingMode"
          selectedKey={pricingMode}
          onSelectionChange={(key) => setPricingMode(key ? String(key) : 'FROM_NOMENCLATURE')}
        >
          {PRICING_MODES.map((mode) => (
            <ListBox.Item key={mode} id={mode} textValue={t(`refundPolicies:pricingMode_${mode}`)}>
              {t(`refundPolicies:pricingMode_${mode}`)}
            </ListBox.Item>
          ))}
        </FormSelectField>
        {pricingMode === 'FIXED' ? (
          <FormTextField label={t('refundPolicies:fixedPriceCents')} name="fixedPriceCents" type="number" value={fixedPriceCents} onChange={setFixedPriceCents} inputProps={{ type: 'number', min: 0, className: 'font-mono' }} />
        ) : null}
        {pricingMode === 'PERCENT' ? (
          <>
            <FormTextField label={t('refundPolicies:percentValue')} name="percentValue" type="number" value={percentValue} onChange={setPercentValue} inputProps={{ type: 'number', min: 0, max: 100, step: 0.01 }} />
            <FormSelectField
              label={t('refundPolicies:percentBasis')}
              name="percentBasis"
              selectedKey={percentBasis}
              onSelectionChange={(key) => {
                percentBasisTouched.current = true;
                setPercentBasis(key ? String(key) : 'ROUTE_PRICE');
              }}
            >
              {PERCENT_BASES.map((basis) => (
                <ListBox.Item key={basis} id={basis} textValue={t(`refundPolicies:percentBasis_${basis}`)}>
                  {t(`refundPolicies:percentBasis_${basis}`)}
                </ListBox.Item>
              ))}
            </FormSelectField>
            <FormTextField label={t('refundPolicies:minPriceCents')} name="minPriceCents" type="number" value={minPriceCents} onChange={setMinPriceCents} inputProps={{ type: 'number', min: 0, className: 'font-mono' }} />
            <FormTextField label={t('refundPolicies:maxPriceCents')} name="maxPriceCents" type="number" value={maxPriceCents} onChange={setMaxPriceCents} inputProps={{ type: 'number', min: 0, className: 'font-mono' }} />
          </>
        ) : null}
        <FormTextField label={t('refundPolicies:serviceFeeCents')} name="serviceFeeCents" type="number" value={serviceFeeCents} onChange={setServiceFeeCents} inputProps={{ type: 'number', min: 0, className: 'font-mono' }} />
        <div className="transora-form-field flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
          <Label>{t('refundPolicies:isActive')}</Label>
          <Switch isSelected={isActive} onChange={setIsActive}>
            <Switch.Control><Switch.Thumb /></Switch.Control>
          </Switch>
        </div>
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-sm font-medium">{t('refundPolicies:thresholds')}</h3>
            <Button size="sm" variant="secondary" type="button" onPress={() => setTiers((rows) => [...rows, emptyTier()])}>
              {t('refundPolicies:addThreshold')}
            </Button>
          </div>
          {tiers.map((tier, index) => (
            <div key={tier.key} className={`space-y-3 rounded-lg border p-3 ${overlapKeys.has(tier.key) ? 'border-danger' : 'border-border'}`}>
              <div className="flex items-center justify-between text-sm font-medium text-muted">
                <span>{t('refundPolicies:thresholds')} #{index + 1}</span>
                {tiers.length > 1 ? (
                  <Button size="sm" variant="secondary" type="button" onPress={() => setTiers((rows) => rows.filter((r) => r.key !== tier.key))}>
                    {t('refundPolicies:removeThreshold')}
                  </Button>
                ) : null}
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                <FormTextField label={t('refundPolicies:hoursBeforeMin')} name={`tier-${tier.key}-min`} type="number" value={tier.hoursBeforeMin} onChange={(value) => updateTier(tier.key, { hoursBeforeMin: value })} inputProps={{ type: 'number', min: 0 }} />
                <FormTextField label={t('refundPolicies:hoursBeforeMax')} name={`tier-${tier.key}-max`} type="number" value={tier.hoursBeforeMax} onChange={(value) => updateTier(tier.key, { hoursBeforeMax: value })} inputProps={{ type: 'number', min: 0 }} />
                {policyType === 'REFUND' ? (
                  <>
                    <FormTextField isRequired label={t('refundPolicies:penaltyPercent')} name={`tier-${tier.key}-penalty`} type="number" value={tier.penaltyPercent} onChange={(value) => updateTier(tier.key, { penaltyPercent: value })} inputProps={{ type: 'number', min: 0, max: 100 }} />
                    <div className="transora-form-field flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
                      <Label>{t('refundPolicies:refundAllowed')}</Label>
                      <Switch isSelected={tier.refundAllowed} onChange={(value) => updateTier(tier.key, { refundAllowed: value })}>
                        <Switch.Control><Switch.Thumb /></Switch.Control>
                      </Switch>
                    </div>
                  </>
                ) : null}
                {pricingMode === 'FIXED' || pricingMode === 'PERCENT' ? (
                  <>
                    {pricingMode === 'FIXED' ? (
                      <FormTextField label={t('refundPolicies:tierFixedPriceCents')} name={`tier-${tier.key}-fixed`} type="number" value={tier.fixedPriceCents} onChange={(value) => updateTier(tier.key, { fixedPriceCents: value })} inputProps={{ type: 'number', min: 0, className: 'font-mono' }} />
                    ) : (
                      <FormTextField label={t('refundPolicies:tierPercentValue')} name={`tier-${tier.key}-pct`} type="number" value={tier.percentValue} onChange={(value) => updateTier(tier.key, { percentValue: value })} inputProps={{ type: 'number', min: 0, max: 100, step: 0.01 }} />
                    )}
                  </>
                ) : null}
              </div>
            </div>
          ))}
        </div>
        {error ? (
          <Alert status="danger">
            <Alert.Indicator />
            <Alert.Content><Alert.Description>{error}</Alert.Description></Alert.Content>
          </Alert>
        ) : null}
      </form>
    </FormDrawer>
  );
}
