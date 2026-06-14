import { useEffect, useState } from 'react';
import { Alert, Label, Switch } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';
import type { AdminStationResponse } from '@transora/api-client';
import { PointMapPicker } from '@/components/map/PointMapPicker';
import { FormDrawer } from '@/components/ui/FormDrawer';
import { FormTextField } from '@/components/ui/FormFields';
import { useCreateStation, useUpdateStation } from './api/hooks';

type StationFormDrawerProps = {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  station?: AdminStationResponse | null;
};

export function StationFormDrawer({ isOpen, onOpenChange, station }: StationFormDrawerProps) {
  const { t } = useTranslation(['stations', 'common']);
  const isEdit = Boolean(station);
  const createStation = useCreateStation();
  const updateStation = useUpdateStation();

  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [city, setCity] = useState('');
  const [address, setAddress] = useState('');
  const [timezone, setTimezone] = useState('Europe/Moscow');
  const [description, setDescription] = useState('');
  const [contactPhone, setContactPhone] = useState('');
  const [latitude, setLatitude] = useState(55.7558);
  const [longitude, setLongitude] = useState(37.6173);
  const [isActive, setIsActive] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!isOpen) return;
    if (station) {
      setCode(station.code);
      setName(station.name);
      setCity(station.city);
      setAddress(station.address ?? '');
      setTimezone(station.timezone);
      setDescription(station.description ?? '');
      setContactPhone(station.contactPhone ?? '');
      setIsActive(station.isActive);
      setLatitude(55.7558);
      setLongitude(37.6173);
    } else {
      setCode('');
      setName('');
      setCity('');
      setAddress('');
      setTimezone('Europe/Moscow');
      setDescription('');
      setContactPhone('');
      setLatitude(55.7558);
      setLongitude(37.6173);
      setIsActive(true);
    }
    setError(null);
  }, [isOpen, station]);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError(null);
    const schema = z.object({
      name: z.string().min(1, t('stations:nameRequired')),
      city: z.string().min(1, t('stations:cityRequired')),
    });
    const parsed = schema.safeParse({ name, city });
    if (!parsed.success) {
      setError(parsed.error.issues[0]?.message ?? t('common:errorGeneric'));
      return;
    }

    try {
      if (isEdit && station) {
        await updateStation.mutateAsync({
          id: station.id,
          payload: {
            name,
            city,
            address: address || undefined,
            timezone,
            description: description || undefined,
            contactPhone: contactPhone || undefined,
            isActive,
            point: { latitude, longitude },
          },
        });
      } else {
        await createStation.mutateAsync({
          code: code || undefined,
          name,
          city,
          address: address || undefined,
          timezone,
          description: description || undefined,
          contactPhone: contactPhone || undefined,
          point: { latitude, longitude },
        });
      }
      onOpenChange(false);
    } catch {
      setError(t('stations:saveError'));
    }
  }

  return (
    <FormDrawer
      isOpen={isOpen}
      onOpenChange={onOpenChange}
      title={isEdit ? t('stations:editTitle') : t('stations:createTitle')}
      formId="station-form"
      isPending={createStation.isPending || updateStation.isPending}
    >
      <form className="transora-form-stack" id="station-form" onSubmit={handleSubmit}>
        {!isEdit ? (
          <FormTextField label={t('stations:code')} name="code" value={code} onChange={setCode} />
        ) : null}
        <FormTextField isRequired label={t('stations:name')} name="name" value={name} onChange={setName} />
        <FormTextField isRequired label={t('stations:city')} name="city" value={city} onChange={setCity} />
        <FormTextField label={t('stations:address')} name="address" value={address} onChange={setAddress} />
        <FormTextField label={t('stations:timezone')} name="timezone" value={timezone} onChange={setTimezone} />
        <FormTextField label={t('stations:descriptionField')} name="description" value={description} onChange={setDescription} />
        <FormTextField label={t('stations:contactPhone')} name="contactPhone" value={contactPhone} onChange={setContactPhone} />
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
        {isEdit ? (
          <div className="transora-form-field flex items-center justify-between gap-3 rounded-lg border border-border px-3 py-2">
            <Label>{t('stations:active')}</Label>
            <Switch isSelected={isActive} onChange={setIsActive}>
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
      </form>
    </FormDrawer>
  );
}
