import { useEffect, useMemo, useState } from 'react';
import { Alert, Label, ListBox, Switch } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';
import type { TariffProfileResponse } from '@transora/api-client';
import { FormDrawer } from '@/components/ui/FormDrawer';
import { FormDateRangePicker } from '@/components/ui/FormDateRangePicker';
import { FormSelectField, FormTextField } from '@/components/ui/FormFields';
import { isIsoDateRangeInvalid } from '@/lib/date-values';
import { useCreateTariffProfile, useUpdateTariffProfile } from './api/hooks';
import { formatRouteLabel, useRoutesList } from './api/routes-hooks';

type TariffProfileFormDrawerProps = {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  profile?: TariffProfileResponse | null;
  onSaved?: (profile: TariffProfileResponse) => void;
};

export function TariffProfileFormDrawer({
  isOpen,
  onOpenChange,
  profile,
  onSaved,
}: TariffProfileFormDrawerProps) {
  const { t } = useTranslation(['tariffProfiles', 'common']);
  const isEdit = Boolean(profile);
  const createProfile = useCreateTariffProfile();
  const updateProfile = useUpdateTariffProfile();
  const { data: routes } = useRoutesList();

  const [name, setName] = useState('');
  const [routeId, setRouteId] = useState('');
  const [validFrom, setValidFrom] = useState('');
  const [validTo, setValidTo] = useState('');
  const [isActive, setIsActive] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const routeOptions = useMemo(
    () => (routes ?? []).slice().sort((a, b) => formatRouteLabel(a).localeCompare(formatRouteLabel(b))),
    [routes],
  );

  useEffect(() => {
    if (!isOpen) return;
    if (profile) {
      setName(profile.name);
      setRouteId(profile.routeId ?? '');
      setValidFrom(profile.validFrom ?? '');
      setValidTo(profile.validTo ?? '');
      setIsActive(profile.isActive);
    } else {
      setName('');
      setRouteId('');
      setValidFrom('');
      setValidTo('');
      setIsActive(true);
    }
    setError(null);
  }, [isOpen, profile]);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    const schema = z.object({ name: z.string().min(1), isActive: z.boolean() });
    const parsed = schema.safeParse({ name, isActive });
    if (!parsed.success) {
      setError(t('tariffProfiles:nameRequired'));
      return;
    }
    if (isIsoDateRangeInvalid(validFrom, validTo)) {
      setError(t('tariffProfiles:invalidDateRange'));
      return;
    }
    const payload = {
      name,
      routeId: routeId || undefined,
      validFrom: validFrom || undefined,
      validTo: validTo || undefined,
      isActive,
    };
    try {
      const saved = isEdit && profile
        ? await updateProfile.mutateAsync({ id: profile.id, payload })
        : await createProfile.mutateAsync(payload);
      onSaved?.(saved);
      onOpenChange(false);
    } catch {
      setError(t('tariffProfiles:saveError'));
    }
  }

  return (
    <FormDrawer
      isOpen={isOpen}
      onOpenChange={onOpenChange}
      title={isEdit ? t('tariffProfiles:editTitle') : t('tariffProfiles:createTitle')}
      formId="tariff-profile-form"
      isPending={createProfile.isPending || updateProfile.isPending}
    >
      <form className="transora-form-stack" id="tariff-profile-form" onSubmit={handleSubmit}>
        <FormTextField isRequired label={t('tariffProfiles:name')} name="name" value={name} onChange={setName} />
        <FormSelectField
          label={t('tariffProfiles:route')}
          name="routeId"
          selectedKey={routeId || null}
          onSelectionChange={(key) => setRouteId(key ? String(key) : '')}
        >
          <ListBox.Item id="" textValue={t('tariffProfiles:noRoute')}>
            {t('tariffProfiles:noRoute')}
          </ListBox.Item>
          {routeOptions.map((route) => (
            <ListBox.Item key={route.id} id={route.id} textValue={formatRouteLabel(route)}>
              {formatRouteLabel(route)}
            </ListBox.Item>
          ))}
        </FormSelectField>
        <FormDateRangePicker
          label={t('tariffProfiles:validPeriod')}
          startName="validFrom"
          endName="validTo"
          startValue={validFrom}
          endValue={validTo}
          errorMessage={t('tariffProfiles:invalidDateRange')}
          onChange={(start, end) => {
            setValidFrom(start);
            setValidTo(end);
          }}
        />
        <p className="text-sm text-muted">{t('tariffProfiles:routePoliciesHint')}</p>
        <div className="transora-form-field flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
          <Label>{t('tariffProfiles:isActive')}</Label>
          <Switch isSelected={isActive} onChange={setIsActive}>
            <Switch.Control><Switch.Thumb /></Switch.Control>
          </Switch>
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
