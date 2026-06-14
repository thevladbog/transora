import { useEffect, useState } from 'react';
import { Alert, Button, Label, ListBox, Switch } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';
import type { RoutePricingBundleResponse } from '@transora/api-client';
import { FormDateRangePicker } from '@/components/ui/FormDateRangePicker';
import { FormSelectField, FormTextField } from '@/components/ui/FormFields';
import { isIsoDateRangeInvalid } from '@/lib/date-values';
import { useCreateRoutePricing, useUpdateRoutePricing } from './api/hooks';
import { useCarriersList } from './api/carriers-hooks';

type RouteMetaSectionProps = {
  mode: 'create' | 'edit';
  bundle?: RoutePricingBundleResponse | null;
  onCreated?: (routeId: string) => void;
  onUpdated?: () => void;
};

export function RouteMetaSection({ mode, bundle, onCreated, onUpdated }: RouteMetaSectionProps) {
  const { t } = useTranslation(['routes', 'common']);
  const createRoute = useCreateRoutePricing();
  const updateRoute = useUpdateRoutePricing(bundle?.routeId ?? '');
  const { data: carriers } = useCarriersList();

  const [carrierId, setCarrierId] = useState('');
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [validFrom, setValidFrom] = useState('');
  const [validTo, setValidTo] = useState('');
  const [isActive, setIsActive] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(mode === 'create');

  useEffect(() => {
    if (mode === 'create') return;
    if (!bundle) return;
    setCarrierId(bundle.carrierId);
    setCode(bundle.code ?? '');
    setName(bundle.name);
    setValidFrom(bundle.validFrom ?? '');
    setValidTo(bundle.validTo ?? '');
    setIsActive(bundle.isActive);
    setDirty(false);
    setError(null);
  }, [mode, bundle]);

  function markDirty() {
    setDirty(true);
  }

  async function handleSave() {
    setError(null);
    const schema = z.object({
      carrierId: z.string().min(1),
      code: z.string().min(1),
      name: z.string().min(1),
    });
    const parsed = schema.safeParse({ carrierId, code, name });
    if (!parsed.success) {
      setError(t('routes:requiredFields'));
      return;
    }
    if (isIsoDateRangeInvalid(validFrom, validTo)) {
      setError(t('routes:invalidDateRange'));
      return;
    }
    const payload = {
      carrierId,
      code: code.trim(),
      name: name.trim(),
      validFrom: validFrom || undefined,
      validTo: validTo || undefined,
      isActive,
    };
    try {
      if (mode === 'create') {
        const saved = await createRoute.mutateAsync(payload);
        onCreated?.(saved.routeId);
      } else {
        await updateRoute.mutateAsync(payload);
        setDirty(false);
        onUpdated?.();
      }
    } catch {
      setError(t('routes:saveError'));
    }
  }

  const isPending = mode === 'create' ? createRoute.isPending : updateRoute.isPending;

  return (
    <section className="space-y-4 rounded-xl border border-border p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-base font-semibold">{t('routes:metaSection')}</h2>
        <Button
          variant="primary"
          isPending={isPending}
          isDisabled={mode === 'edit' && !dirty}
          onPress={() => void handleSave()}
        >
          {mode === 'create' ? t('routes:createAndContinue') : t('common:save')}
        </Button>
      </div>
      <div className="grid gap-4 lg:grid-cols-2">
        <FormSelectField
          isRequired
          label={t('routes:carrier')}
          name="carrierId"
          selectedKey={carrierId || null}
          onSelectionChange={(key) => {
            setCarrierId(key ? String(key) : '');
            markDirty();
          }}
        >
          {(carriers ?? []).map((carrier) => (
            <ListBox.Item key={carrier.id} id={carrier.id} textValue={carrier.name}>
              {carrier.name}
            </ListBox.Item>
          ))}
        </FormSelectField>
        <FormTextField
          isRequired
          label={t('routes:internalCode')}
          name="code"
          value={code}
          onChange={(value) => {
            setCode(value);
            markDirty();
          }}
        />
        <FormTextField
          isRequired
          label={t('routes:routeName')}
          name="name"
          value={name}
          onChange={(value) => {
            setName(value);
            markDirty();
          }}
        />
        <FormDateRangePicker
          label={t('routes:validPeriod')}
          startName="validFrom"
          endName="validTo"
          startValue={validFrom}
          endValue={validTo}
          errorMessage={t('routes:invalidDateRange')}
          onChange={(start, end) => {
            setValidFrom(start);
            setValidTo(end);
            markDirty();
          }}
        />
      </div>
      {mode === 'edit' ? (
        <div className="transora-form-field flex max-w-sm items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
          <Label>{t('routes:isActive')}</Label>
          <Switch
            isSelected={isActive}
            onChange={(value) => {
              setIsActive(value);
              markDirty();
            }}
          >
            <Switch.Control>
              <Switch.Thumb />
            </Switch.Control>
          </Switch>
        </div>
      ) : null}
      {error ? (
        <Alert status="danger">
          <Alert.Indicator />
          <Alert.Content>
            <Alert.Description>{error}</Alert.Description>
          </Alert.Content>
        </Alert>
      ) : null}
      {mode === 'create' ? (
        <p className="text-sm text-muted">{t('routes:createHint')}</p>
      ) : null}
    </section>
  );
}
