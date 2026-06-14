import { Button, Label, ListBox } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { VehicleResponse } from '@transora/api-client';
import { FormSelectField, FormTextField } from '@/components/ui/FormFields';
import { FormTimeField } from '@/components/ui/FormTimeField';
import { isValidTimeString } from '@/lib/date-values';
import { useVehiclesList } from '@/features/trips/api/hooks';

export type ScheduleEntryForm = {
  clientId: string;
  tripNumber: string;
  departureTime: string;
  daysOfWeek: number[];
  defaultVehicleId: string;
  isActive: boolean;
};

type ScheduleEntriesEditorProps = {
  entries: ScheduleEntryForm[];
  onChange: (entries: ScheduleEntryForm[]) => void;
};

const ISO_DAYS = [1, 2, 3, 4, 5, 6, 7] as const;

function newEntry(): ScheduleEntryForm {
  return {
    clientId: crypto.randomUUID(),
    tripNumber: '',
    departureTime: '08:00',
    daysOfWeek: [1, 2, 3, 4, 5],
    defaultVehicleId: '',
    isActive: true,
  };
}

export function createEmptyScheduleEntry(): ScheduleEntryForm {
  return newEntry();
}

export function mapResponseEntryToForm(entry: {
  tripNumber: string;
  departureTime: string;
  daysOfWeek: number[];
  defaultVehicleId?: string;
  isActive: boolean;
}): ScheduleEntryForm {
  return {
    clientId: crypto.randomUUID(),
    tripNumber: entry.tripNumber,
    departureTime: entry.departureTime,
    daysOfWeek: [...entry.daysOfWeek],
    defaultVehicleId: entry.defaultVehicleId ?? '',
    isActive: entry.isActive,
  };
}

export function validateScheduleEntries(entries: ScheduleEntryForm[]): boolean {
  if (entries.length === 0) return false;
  return entries.every(
    (entry) =>
      entry.tripNumber.trim().length > 0 &&
      isValidTimeString(entry.departureTime) &&
      entry.daysOfWeek.length > 0,
  );
}

export function ScheduleEntriesEditor({ entries, onChange }: ScheduleEntriesEditorProps) {
  const { t } = useTranslation('schedules');
  const { data: vehicles } = useVehiclesList();

  function updateEntry(clientId: string, patch: Partial<ScheduleEntryForm>) {
    onChange(entries.map((entry) => (entry.clientId === clientId ? { ...entry, ...patch } : entry)));
  }

  function toggleDay(clientId: string, day: number) {
    const entry = entries.find((item) => item.clientId === clientId);
    if (!entry) return;
    const nextDays = entry.daysOfWeek.includes(day)
      ? entry.daysOfWeek.filter((value) => value !== day)
      : [...entry.daysOfWeek, day].sort((a, b) => a - b);
    updateEntry(clientId, { daysOfWeek: nextDays });
  }

  function removeEntry(clientId: string) {
    onChange(entries.filter((entry) => entry.clientId !== clientId));
  }

  function addEntry() {
    onChange([...entries, newEntry()]);
  }

  const activeVehicles = (vehicles ?? []).filter((vehicle: VehicleResponse) => vehicle.isActive);

  return (
    <section className="space-y-4 rounded-xl border border-border p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-base font-semibold">{t('entriesSection')}</h2>
        <Button variant="secondary" onPress={addEntry}>
          {t('addEntry')}
        </Button>
      </div>

      {entries.length === 0 ? <p className="text-sm text-muted">{t('entriesEmpty')}</p> : null}

      <div className="space-y-4">
        {entries.map((entry, index) => (
          <article key={entry.clientId} className="space-y-4 rounded-lg border border-border p-4">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 className="text-sm font-medium">{t('entryTitle', { index: index + 1 })}</h3>
              <Button size="sm" variant="secondary" onPress={() => removeEntry(entry.clientId)}>
                {t('removeEntry')}
              </Button>
            </div>
            <div className="grid gap-4 lg:grid-cols-2">
              <FormTextField
                isRequired
                label={t('tripNumber')}
                value={entry.tripNumber}
                onChange={(tripNumber) => updateEntry(entry.clientId, { tripNumber })}
              />
              <FormTimeField
                isRequired
                label={t('departureTime')}
                value={entry.departureTime}
                onChange={(departureTime) => updateEntry(entry.clientId, { departureTime })}
              />
              <FormSelectField
                className="lg:col-span-2"
                label={t('defaultVehicle')}
                selectedKey={entry.defaultVehicleId || null}
                onSelectionChange={(key) =>
                  updateEntry(entry.clientId, { defaultVehicleId: key ? String(key) : '' })
                }
              >
                {activeVehicles.map((vehicle: VehicleResponse) => (
                  <ListBox.Item
                    key={vehicle.id}
                    id={vehicle.id}
                    textValue={`${vehicle.plateNumber} ${vehicle.model}`}
                  >
                    {vehicle.plateNumber} — {vehicle.model}
                  </ListBox.Item>
                ))}
              </FormSelectField>
            </div>
            <div className="space-y-2">
              <Label>{t('daysOfWeek')}</Label>
              <div className="flex flex-wrap gap-2">
                {ISO_DAYS.map((day) => {
                  const selected = entry.daysOfWeek.includes(day);
                  return (
                    <Button
                      key={day}
                      size="sm"
                      variant={selected ? 'primary' : 'secondary'}
                      onPress={() => toggleDay(entry.clientId, day)}
                    >
                      {t(`weekday.${day}`)}
                    </Button>
                  );
                })}
              </div>
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}
