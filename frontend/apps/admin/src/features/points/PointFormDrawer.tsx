import { useEffect, useState } from 'react';
import { Alert, Label, Switch } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';
import type { PointResponse } from '@transora/api-client';
import { PointMapPicker } from '@/components/map/PointMapPicker';
import { FormDrawer } from '@/components/ui/FormDrawer';
import { FormTextField } from '@/components/ui/FormFields';
import { useCreatePoint, useUpdatePoint } from './api/hooks';

type PointFormDrawerProps = {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  point?: PointResponse | null;
};

export function PointFormDrawer({ isOpen, onOpenChange, point }: PointFormDrawerProps) {
  const { t } = useTranslation(['points', 'common']);
  const isEdit = Boolean(point);
  const createPoint = useCreatePoint();
  const updatePoint = useUpdatePoint();

  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [city, setCity] = useState('');
  const [address, setAddress] = useState('');
  const [latitude, setLatitude] = useState(55.7558);
  const [longitude, setLongitude] = useState(37.6173);
  const [isActive, setIsActive] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    if (point) {
      setCode(point.code);
      setName(point.name);
      setCity(point.city);
      setAddress(point.address ?? '');
      setLatitude(point.latitude);
      setLongitude(point.longitude);
      setIsActive(point.isActive);
    } else {
      setCode('');
      setName('');
      setCity('');
      setAddress('');
      setLatitude(55.7558);
      setLongitude(37.6173);
      setIsActive(true);
    }
    setError(null);
  }, [isOpen, point]);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    const schema = z.object({
      name: z.string().min(1, t('points:nameRequired')),
      latitude: z.number(),
      longitude: z.number(),
      isActive: z.boolean(),
    });
    const parsed = schema.safeParse({ name, latitude, longitude, isActive });
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? t('common:errorGeneric'));
      return;
    }
    const payload = { code, name, city, address, latitude, longitude, isActive };
    try {
      if (isEdit && point) {
        await updatePoint.mutateAsync({ id: point.id, payload });
      } else {
        await createPoint.mutateAsync(payload);
      }
      onOpenChange(false);
    } catch {
      setError(t('points:saveError'));
    }
  }

  return (
    <FormDrawer
      isOpen={isOpen}
      onOpenChange={onOpenChange}
      title={isEdit ? t('points:editTitle') : t('points:createTitle')}
      description={!isEdit ? t('points:createDescription') : undefined}
      formId="point-form"
      isPending={createPoint.isPending || updatePoint.isPending}
    >
      <form className="transora-form-stack" id="point-form" onSubmit={handleSubmit}>
        <FormTextField label={t('points:code')} name="code" value={code} onChange={setCode} />
        <FormTextField isRequired label={t('points:name')} name="name" value={name} onChange={setName} />
        <FormTextField label={t('points:city')} name="city" value={city} onChange={setCity} />
        <FormTextField label={t('points:address')} name="address" value={address} onChange={setAddress} />
        <PointMapPicker
          latitude={latitude}
          longitude={longitude}
          address={address}
          city={city}
          onLocationChange={(patch) => {
            if (patch.latitude != null) setLatitude(patch.latitude);
            if (patch.longitude != null) setLongitude(patch.longitude);
            if (patch.address != null) setAddress(patch.address);
            if (patch.city != null) setCity(patch.city);
          }}
        />
        <div className="transora-form-field flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
          <Label>{t('points:isActive')}</Label>
          <Switch isSelected={isActive} onChange={setIsActive}>
            <Switch.Control>
              <Switch.Thumb />
            </Switch.Control>
          </Switch>
        </div>
        {error ? (
          <Alert status="danger">
            <Alert.Indicator />
            <Alert.Content>
              <Alert.Description>{error}</Alert.Description>
            </Alert.Content>
          </Alert>
        ) : null}
      </form>
    </FormDrawer>
  );
}
