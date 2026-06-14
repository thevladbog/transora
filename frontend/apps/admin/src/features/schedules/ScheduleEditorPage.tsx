import { useEffect, useState } from 'react';
import { Alert, Button } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import {
  CreateScheduleRequestScheduleType,
  type CreateScheduleRequest,
  type ScheduleEntryRequest,
  type UpdateScheduleRequest,
} from '@transora/api-client';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { isIsoDateRangeInvalid } from '@/lib/date-values';
import { useCreateSchedule, useSchedule, useUpdateSchedule } from './api/hooks';
import {
  createEmptyScheduleEntry,
  mapResponseEntryToForm,
  ScheduleEntriesEditor,
  validateScheduleEntries,
  type ScheduleEntryForm,
} from './ScheduleEntriesEditor';
import { ScheduleMetaSection, type ScheduleMetaValues } from './ScheduleMetaSection';

function entriesToRequest(entries: ScheduleEntryForm[]): ScheduleEntryRequest[] {
  return entries.map((entry) => ({
    tripNumber: entry.tripNumber.trim(),
    departureTime: entry.departureTime,
    daysOfWeek: entry.daysOfWeek,
    ...(entry.defaultVehicleId ? { defaultVehicleId: entry.defaultVehicleId } : {}),
    isActive: entry.isActive,
  }));
}

function validateMeta(values: ScheduleMetaValues): string | null {
  if (!values.name.trim()) return 'requiredFields';
  if (!values.routeId) return 'requiredFields';

  if (values.scheduleType === CreateScheduleRequestScheduleType.SEASONAL) {
    if (!values.validFrom || !values.validTo) return 'datesRequired';
    if (isIsoDateRangeInvalid(values.validFrom, values.validTo)) return 'invalidSeasonalRange';
    if (values.validFrom === values.validTo) return 'invalidSeasonalRange';
  }

  if (values.scheduleType === CreateScheduleRequestScheduleType.EXCEPTION) {
    if (!values.validFrom) return 'datesRequired';
  }

  return null;
}

const defaultMeta = (): ScheduleMetaValues => ({
  name: '',
  routeId: '',
  scheduleType: CreateScheduleRequestScheduleType.PERMANENT,
  validFrom: '',
  validTo: '',
  isActive: true,
});

export function ScheduleEditorPage() {
  const { scheduleId = '' } = useParams();
  const navigate = useNavigate();
  const isNew = scheduleId === 'new';
  const { t } = useTranslation(['schedules', 'common']);
  const { data: schedule, isLoading, isError, refetch } = useSchedule(isNew ? '' : scheduleId);
  const createSchedule = useCreateSchedule();
  const updateSchedule = useUpdateSchedule(isNew ? '' : scheduleId);

  const [meta, setMeta] = useState<ScheduleMetaValues>(defaultMeta);
  const [entries, setEntries] = useState<ScheduleEntryForm[]>([createEmptyScheduleEntry()]);
  const [error, setError] = useState<string | null>(null);
  const [dirty, setDirty] = useState(isNew);

  useEffect(() => {
    if (isNew || !schedule) return;
    setMeta({
      name: schedule.name,
      routeId: schedule.routeId,
      scheduleType: schedule.scheduleType as ScheduleMetaValues['scheduleType'],
      validFrom: schedule.validFrom ?? '',
      validTo: schedule.validTo ?? '',
      isActive: schedule.isActive,
    });
    setEntries(
      schedule.entries.length > 0
        ? schedule.entries.map(mapResponseEntryToForm)
        : [createEmptyScheduleEntry()],
    );
    setDirty(false);
    setError(null);
  }, [isNew, schedule]);

  function patchMeta(patch: Partial<ScheduleMetaValues>) {
    setMeta((current) => ({ ...current, ...patch }));
    setDirty(true);
  }

  function patchEntries(nextEntries: ScheduleEntryForm[]) {
    setEntries(nextEntries);
    setDirty(true);
  }

  async function handleSave() {
    setError(null);

    const metaError = validateMeta(meta);
    if (metaError) {
      setError(t(metaError));
      return;
    }
    if (!validateScheduleEntries(entries)) {
      setError(t('invalidEntries'));
      return;
    }

    const isPermanent = meta.scheduleType === CreateScheduleRequestScheduleType.PERMANENT;
    const isException = meta.scheduleType === CreateScheduleRequestScheduleType.EXCEPTION;
    const validFrom = isPermanent ? undefined : meta.validFrom || undefined;
    const validTo = isPermanent ? undefined : isException ? meta.validFrom : meta.validTo || undefined;

    try {
      if (isNew) {
        const payload: CreateScheduleRequest = {
          routeId: meta.routeId,
          name: meta.name.trim(),
          scheduleType: meta.scheduleType,
          validFrom,
          validTo,
          entries: entriesToRequest(entries),
        };
        const created = await createSchedule.mutateAsync(payload);
        navigate(`/schedules/${created.id}`, { replace: true });
      } else {
        const payload: UpdateScheduleRequest = {
          name: meta.name.trim(),
          scheduleType: meta.scheduleType,
          validFrom,
          validTo,
          isActive: meta.isActive,
          entries: entriesToRequest(entries),
        };
        await updateSchedule.mutateAsync(payload);
        setDirty(false);
        void refetch();
      }
    } catch (err) {
      const message = (err as Error).message;
      setError(
        message && message !== `HTTP ${(err as { status?: number }).status}` ? message : t('saveError'),
      );
    }
  }

  const title = isNew
    ? t('createTitle')
    : schedule
      ? schedule.name
      : t('editorTitle');

  const isPending = isNew ? createSchedule.isPending : updateSchedule.isPending;

  return (
    <div className="space-y-6">
      <PageHeader
        title={title}
        description={isNew ? t('editorDescription') : undefined}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="primary"
              isPending={isPending}
              isDisabled={!isNew && !dirty}
              onPress={() => void handleSave()}
            >
              {isNew ? t('create') : t('common:save')}
            </Button>
            <Link
              to="/schedules"
              className="inline-flex items-center rounded-lg border border-border px-3 py-2 text-sm hover:bg-default"
            >
              {t('backToList')}
            </Link>
          </div>
        }
      />

      {error ? (
        <Alert status="danger">
          <Alert.Indicator />
          <Alert.Content>
            <Alert.Description>{error}</Alert.Description>
          </Alert.Content>
        </Alert>
      ) : null}

      {isNew ? (
        <>
          <ScheduleMetaSection mode="create" values={meta} onChange={patchMeta} />
          <ScheduleEntriesEditor entries={entries} onChange={patchEntries} />
        </>
      ) : (
        <QueryState
          isLoading={isLoading}
          isError={isError}
          isEmpty={false}
          errorMessage={t('loadError')}
          emptyMessage=""
        >
          {schedule ? (
            <>
              <ScheduleMetaSection mode="edit" values={meta} onChange={patchMeta} routeLocked />
              <ScheduleEntriesEditor entries={entries} onChange={patchEntries} />
            </>
          ) : null}
        </QueryState>
      )}
    </div>
  );
}
