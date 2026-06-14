import { useEffect, useMemo, useState } from 'react';
import { Alert, Label, ListBox, Switch } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { CreateTripFromRouteRequest, VehicleResponse } from '@transora/api-client';
import { FormDrawer } from '@/components/ui/FormDrawer';
import { FormDatePicker } from '@/components/ui/FormDatePicker';
import { FormSelectField, FormTextField } from '@/components/ui/FormFields';
import { FormTimeField } from '@/components/ui/FormTimeField';
import { isIsoDateBeforeToday, todayIsoDate } from '@/lib/date-values';
import { useRoutesPricingList } from '@/features/routes/api/hooks';
import { formatRouteLabel } from '@/features/routes/route-label';
import { useCreateTripFromRoute, useVehiclesList } from './api/hooks';

type CreateTripFromRouteDrawerProps = {
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  routeId?: string;
  stationCode?: string;
  onSuccess?: () => void;
};

export function CreateTripFromRouteDrawer({
  isOpen,
  onOpenChange,
  routeId: presetRouteId,
  stationCode,
  onSuccess,
}: CreateTripFromRouteDrawerProps) {
  const { t } = useTranslation('trips');
  const createTrip = useCreateTripFromRoute(stationCode);
  const { data: routes } = useRoutesPricingList();

  const [selectedRouteId, setSelectedRouteId] = useState(presetRouteId ?? '');
  const [tripDate, setTripDate] = useState(todayIsoDate());
  const [departureTime, setDepartureTime] = useState('09:00');
  const [tripNumber, setTripNumber] = useState('');
  const [platform, setPlatform] = useState('');
  const [openSales, setOpenSales] = useState(false);
  const [vehicleId, setVehicleId] = useState('');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const { data: vehicles } = useVehiclesList(openSales);

  const routeOptions = useMemo(
    () => (routes ?? []).filter((route) => route.isActive && route.stopCount >= 2),
    [routes],
  );

  const effectiveRouteId = presetRouteId ?? selectedRouteId;

  useEffect(() => {
    if (!isOpen) return;
    setSelectedRouteId(presetRouteId ?? '');
    setTripDate(todayIsoDate());
    setDepartureTime('09:00');
    setTripNumber('');
    setPlatform('');
    setOpenSales(false);
    setVehicleId('');
    setErrorMessage(null);
  }, [isOpen, presetRouteId]);

  const needsVehicle = openSales && !vehicleId;
  const needsRoute = !effectiveRouteId;
  const needsTripNumber = !tripNumber.trim();
  const pastDate = isIsoDateBeforeToday(tripDate);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setErrorMessage(null);

    if (needsRoute) {
      setErrorMessage(t('selectRoute'));
      return;
    }
    if (pastDate) {
      setErrorMessage(t('pastDate'));
      return;
    }
    if (needsTripNumber) {
      setErrorMessage(t('tripNumberRequired'));
      return;
    }
    if (needsVehicle) {
      setErrorMessage(t('needsVehicle'));
      return;
    }

    const payload: CreateTripFromRouteRequest = {
      routeId: effectiveRouteId,
      tripDate,
      departureTime,
      tripNumber: tripNumber.trim(),
      ...(platform.trim() ? { platform: platform.trim() } : {}),
      ...(openSales ? { openSales: true, vehicleId } : {}),
    };

    try {
      await createTrip.mutateAsync(payload);
      onSuccess?.();
      onOpenChange(false);
    } catch (error) {
      const message = (error as Error).message;
      setErrorMessage(message && message !== `HTTP ${(error as { status?: number }).status}` ? message : t('createError'));
    }
  }

  return (
    <FormDrawer
      isOpen={isOpen}
      onOpenChange={onOpenChange}
      title={t('createTripTitle')}
      description={t('createTripDescription')}
      formId="create-trip-from-route"
      isPending={createTrip.isPending}
      submitLabel={t('createTrip')}
    >
      <form id="create-trip-from-route" className="transora-form-stack" onSubmit={(event) => void handleSubmit(event)}>
        {errorMessage ? (
          <Alert status="danger">
            <Alert.Indicator />
            <Alert.Content>
              <Alert.Description>{errorMessage}</Alert.Description>
            </Alert.Content>
          </Alert>
        ) : null}

        {!presetRouteId ? (
          <FormSelectField
            label={t('route')}
            isRequired
            selectedKey={selectedRouteId || undefined}
            onSelectionChange={(key) => setSelectedRouteId(String(key))}
          >
            {routeOptions.map((route) => (
              <ListBox.Item
                key={route.routeId}
                id={route.routeId}
                textValue={formatRouteLabel(route)}
              >
                {formatRouteLabel(route)}
              </ListBox.Item>
            ))}
          </FormSelectField>
        ) : null}

        <FormDatePicker label={t('tripDate')} value={tripDate} onChange={setTripDate} isRequired />
        <FormTimeField label={t('departureTime')} value={departureTime} onChange={setDepartureTime} isRequired />

        <FormTextField
          isRequired
          label={t('tripNumber')}
          value={tripNumber}
          onChange={setTripNumber}
        />
        <p className="text-sm text-muted">{t('tripNumberHint')}</p>

        <FormTextField label={t('platform')} value={platform} onChange={setPlatform} />

        <div className="transora-form-field flex items-center justify-between gap-4 rounded-lg border border-border px-3 py-2">
          <Label>{t('openSales')}</Label>
          <Switch isSelected={openSales} onChange={setOpenSales} />
        </div>

        {openSales ? (
          <FormSelectField
            label={t('vehicle')}
            isRequired
            selectedKey={vehicleId || undefined}
            onSelectionChange={(key) => setVehicleId(String(key))}
          >
            {(vehicles ?? [])
              .filter((vehicle: VehicleResponse) => vehicle.isActive)
              .map((vehicle: VehicleResponse) => (
                <ListBox.Item
                  key={vehicle.id}
                  id={vehicle.id}
                  textValue={`${vehicle.plateNumber} ${vehicle.model}`}
                >
                  {vehicle.plateNumber} — {vehicle.model} ({vehicle.totalSeats})
                </ListBox.Item>
              ))}
          </FormSelectField>
        ) : null}
      </form>
    </FormDrawer>
  );
}
