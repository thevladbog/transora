import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Chip, ListBox, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import {
  UpdateTripRequestStatus,
  type UpdateTripRequest,
  type VehicleResponse,
} from '@transora/api-client';
import { useCanAccess } from '@/components/layout/PermissionGate';
import { FormDrawer } from '@/components/ui/FormDrawer';
import { FormNumberField, FormSelectField, FormTextField } from '@/components/ui/FormFields';
import { QueryState } from '@/components/ui/QueryState';
import { Permissions } from '@/lib/permissions';
import { useTrip, useUpdateTrip, useVehiclesList } from './api/hooks';
import { getAllowedNextStatuses, isResourcesLocked, isTerminal } from './trip-status';

type TripDetailDrawerProps = {
  tripId: string | null;
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  stationCode?: string;
  onSuccess?: () => void;
};

export function TripDetailDrawer({
  tripId,
  isOpen,
  onOpenChange,
  stationCode,
  onSuccess,
}: TripDetailDrawerProps) {
  const { t } = useTranslation(['trips', 'common']);
  const canEdit = useCanAccess(Permissions.SCHEDULE_EDIT);
  const { data: detail, isLoading, isError } = useTrip(tripId, { includeStops: true });
  const updateTrip = useUpdateTrip(stationCode);
  const { data: vehicles } = useVehiclesList(isOpen && canEdit);

  const trip = detail?.trip;
  const stops = detail?.stops ?? [];

  const [status, setStatus] = useState('');
  const [platform, setPlatform] = useState('');
  const [delayMinutes, setDelayMinutes] = useState('0');
  const [delayReason, setDelayReason] = useState('');
  const [vehicleId, setVehicleId] = useState('');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const readOnly = !canEdit || (trip ? isTerminal(trip.status) : false);
  const resourcesLocked = trip ? isResourcesLocked(trip.status) : false;

  const statusOptions = useMemo(
    () => (trip ? getAllowedNextStatuses(trip.status) : []),
    [trip],
  );

  useEffect(() => {
    if (!isOpen || !trip) return;
    setStatus(trip.status);
    setPlatform(trip.platform ?? '');
    setDelayMinutes(trip.delayMinutes != null ? String(trip.delayMinutes) : '0');
    setDelayReason('');
    setVehicleId(trip.vehicleId ?? '');
    setErrorMessage(null);
  }, [isOpen, trip]);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (!trip || readOnly) return;
    setErrorMessage(null);

    const effectiveVehicleId = vehicleId || trip.vehicleId;
    if (status === UpdateTripRequestStatus.OPEN && !effectiveVehicleId) {
      setErrorMessage(t('trips:needsVehicleForOpen'));
      return;
    }

    const payload: UpdateTripRequest = {};
    if (status !== trip.status) {
      payload.status = status as UpdateTripRequestStatus;
    }
    const trimmedPlatform = platform.trim();
    if (trimmedPlatform !== (trip.platform ?? '')) {
      payload.platform = trimmedPlatform || undefined;
    }

    const delayNum = delayMinutes === '' ? 0 : Number(delayMinutes);
    const origDelay = trip.delayMinutes ?? 0;
    if (!Number.isNaN(delayNum) && delayNum !== origDelay) {
      payload.delayMinutes = delayNum;
      if (delayReason.trim()) {
        payload.delayReason = delayReason.trim();
      }
    }

    if (!resourcesLocked && vehicleId && vehicleId !== (trip.vehicleId ?? '')) {
      payload.vehicleId = vehicleId;
    }

    if (Object.keys(payload).length === 0) {
      onOpenChange(false);
      return;
    }

    try {
      await updateTrip.mutateAsync({ tripId: trip.id, payload });
      onSuccess?.();
      onOpenChange(false);
    } catch (error) {
      const err = error as Error & { detail?: string; status?: number };
      const message = err.detail ?? err.message;
      setErrorMessage(
        message && message !== `HTTP ${err.status}` ? message : t('trips:saveError'),
      );
    }
  }

  const readOnlyFooter = (
    <Button variant="secondary" onPress={() => onOpenChange(false)}>
      {t('common:close')}
    </Button>
  );

  if (!isOpen) {
    return null;
  }

  return (
    <FormDrawer
      isOpen={isOpen}
      onOpenChange={onOpenChange}
      title={t('trips:detailTitle')}
      description={t('trips:detailDescription')}
      formId="trip-detail-form"
      size="xl"
      isPending={updateTrip.isPending}
      submitLabel={t('common:save')}
      footer={readOnly ? readOnlyFooter : undefined}
    >
      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={false}
        errorMessage={t('trips:detailLoadError')}
      >
        {trip ? (
          <form
            id="trip-detail-form"
            className="transora-form-stack"
            onSubmit={(event) => void handleSubmit(event)}
          >
            {errorMessage ? (
              <Alert status="danger">
                <Alert.Indicator />
                <Alert.Content>
                  <Alert.Description>{errorMessage}</Alert.Description>
                </Alert.Content>
              </Alert>
            ) : null}

            {readOnly && canEdit ? (
              <p className="text-sm text-muted">{t('trips:readOnlyHint')}</p>
            ) : null}
            {!canEdit ? (
              <p className="text-sm text-muted">{t('trips:viewOnlyHint')}</p>
            ) : null}

            <section className="space-y-2 rounded-lg border border-border p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-semibold">
                  {trip.tripNumber ?? trip.routeNumber}
                </span>
                <Chip size="sm" variant="secondary">
                  {t(`trips:status.${trip.status}`)}
                </Chip>
                {(trip.delayMinutes ?? 0) > 0 ? (
                  <Chip size="sm" variant="secondary">
                    +{trip.delayMinutes} {t('trips:delayMinutesShort')}
                  </Chip>
                ) : null}
              </div>
              <p className="text-sm">
                {trip.routeNumber}: {trip.departureStation} → {trip.arrivalStation}
              </p>
              <div className="grid gap-1 text-sm font-mono">
                <div>
                  <span className="text-muted">{t('trips:departure')}: </span>
                  {trip.departureTime}
                </div>
                <div>
                  <span className="text-muted">{t('trips:expectedDeparture')}: </span>
                  {trip.expectedDepartureTime}
                </div>
              </div>
            </section>

            {stops.length > 0 ? (
              <section className="space-y-2">
                <h3 className="text-sm font-semibold">{t('trips:stops')}</h3>
                <Table aria-label={t('trips:stops')} variant="secondary">
                  <Table.ScrollContainer>
                    <Table.Content>
                      <Table.Header>
                        <Table.Column>#</Table.Column>
                        <Table.Column>{t('trips:stopName')}</Table.Column>
                        <Table.Column>{t('trips:scheduledDeparture')}</Table.Column>
                        <Table.Column>{t('trips:estimatedDeparture')}</Table.Column>
                      </Table.Header>
                      <Table.Body items={stops}>
                        {(stop) => (
                          <Table.Row id={stop.id}>
                            <Table.Cell>{stop.stopOrder}</Table.Cell>
                            <Table.Cell>{stop.stopName}</Table.Cell>
                            <Table.Cell className="font-mono text-xs">
                              {stop.scheduledDeparture}
                            </Table.Cell>
                            <Table.Cell className="font-mono text-xs">
                              {stop.estimatedDeparture ?? '—'}
                            </Table.Cell>
                          </Table.Row>
                        )}
                      </Table.Body>
                    </Table.Content>
                  </Table.ScrollContainer>
                </Table>
              </section>
            ) : null}

            {!readOnly ? (
              <>
                <FormSelectField
                  label={t('trips:status')}
                  selectedKey={status || undefined}
                  onSelectionChange={(key) => setStatus(String(key))}
                >
                  {statusOptions.map((option) => (
                    <ListBox.Item key={option} id={option} textValue={t(`trips:status.${option}`)}>
                      {t(`trips:status.${option}`)}
                    </ListBox.Item>
                  ))}
                </FormSelectField>

                <FormTextField label={t('trips:platform')} value={platform} onChange={setPlatform} />

                <FormNumberField
                  label={t('trips:delay')}
                  value={delayMinutes}
                  onChange={setDelayMinutes}
                  min={0}
                />

                <FormTextField
                  label={t('trips:delayReason')}
                  value={delayReason}
                  onChange={setDelayReason}
                />

                {!resourcesLocked ? (
                  <>
                    <FormSelectField
                      label={t('trips:vehicle')}
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
                    {status === UpdateTripRequestStatus.OPEN && !vehicleId && !trip.vehicleId ? (
                      <p className="text-sm text-muted">{t('trips:needsVehicleForOpen')}</p>
                    ) : null}
                  </>
                ) : (
                  <p className="text-sm text-muted">{t('trips:resourcesLocked')}</p>
                )}
              </>
            ) : null}
          </form>
        ) : null}
      </QueryState>
    </FormDrawer>
  );
}
