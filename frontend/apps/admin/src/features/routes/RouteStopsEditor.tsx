import { useEffect, useState } from 'react';
import { Alert, Button, Chip, Label, ListBox, NumberField, Select } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { RoutePricingStopResponse } from '@transora/api-client';
import { usePointsList } from '@/features/points/api/hooks';
import { useSyncRouteStops } from './api/hooks';

type RouteStopsEditorProps = {
  routeId: string;
  stops: RoutePricingStopResponse[];
  onSaved?: () => void;
};

export function RouteStopsEditor({ routeId, stops, onSaved }: RouteStopsEditorProps) {
  const { t } = useTranslation(['routes', 'common']);
  const { data: points } = usePointsList();
  const syncStops = useSyncRouteStops(routeId);
  const [selectedPointId, setSelectedPointId] = useState('');
  const [pointIds, setPointIds] = useState<string[]>([]);
  const [legDurations, setLegDurations] = useState<number[]>([]);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    setPointIds(stops.map((s) => s.pointId));
    setLegDurations(stops.slice(1).map((s) => s.scheduledDurationMin ?? 60));
    setDirty(false);
  }, [stops]);

  const stopByPointId = new Map(stops.map((s) => [s.pointId, s]));

  function addStop() {
    if (!selectedPointId || pointIds.includes(selectedPointId)) return;
    setPointIds((prev) => [...prev, selectedPointId]);
    if (pointIds.length >= 1) {
      setLegDurations((prev) => [...prev, 60]);
    }
    setSelectedPointId('');
    setDirty(true);
  }

  function moveStop(index: number, direction: -1 | 1) {
    setPointIds((prev) => {
      const next = [...prev];
      const target = index + direction;
      if (target < 0 || target >= next.length) return prev;
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
    setDirty(true);
  }

  function removeStop(index: number) {
    setPointIds((prev) => prev.filter((_, i) => i !== index));
    if (index > 0) {
      setLegDurations((prev) => prev.filter((_, i) => i !== index - 1));
    } else if (legDurations.length > 0) {
      setLegDurations((prev) => prev.slice(1));
    }
    setDirty(true);
  }

  function updateLegDuration(index: number, value: number) {
    setLegDurations((prev) => prev.map((d, i) => (i === index ? value : d)));
    setDirty(true);
  }

  async function save() {
    await syncStops.mutateAsync({ pointIds, legDurationsMin: legDurations });
    setDirty(false);
    onSaved?.();
  }

  return (
    <section className="space-y-3 rounded-xl border border-border p-4">
      <h2 className="text-base font-semibold">{t('routes:stationsSection')}</h2>
      <div className="flex flex-wrap items-end gap-2">
        <div className="min-w-64 flex-1">
          <Select
            variant="secondary"
            fullWidth
            selectedKey={selectedPointId || null}
            onSelectionChange={(key) => setSelectedPointId(key ? String(key) : '')}
          >
            <Select.Trigger>
              <Select.Value />
              <Select.Indicator />
            </Select.Trigger>
            <Select.Popover>
              <ListBox>
                {(points ?? []).map((point) => (
                  <ListBox.Item key={point.id} id={point.id} textValue={point.name}>
                    {point.name} ({point.city})
                  </ListBox.Item>
                ))}
              </ListBox>
            </Select.Popover>
          </Select>
        </div>
        <Button variant="primary" onPress={addStop}>{t('routes:addStop')}</Button>
        <Button variant="secondary" isPending={syncStops.isPending} isDisabled={!dirty} onPress={() => void save()}>
          {t('common:save')}
        </Button>
      </div>
      {syncStops.isError ? (
        <Alert status="danger">
          <Alert.Indicator />
          <Alert.Content><Alert.Description>{t('routes:saveError')}</Alert.Description></Alert.Content>
        </Alert>
      ) : null}
      <ol className="space-y-2">
        {pointIds.map((pointId, index) => {
          const meta = stopByPointId.get(pointId);
          const point = (points ?? []).find((p) => p.id === pointId);
          const label = meta?.pointName ?? point?.name ?? pointId;
          const city = meta?.pointCity ?? point?.city;
          const isBranch = meta?.isBranch ?? false;
          return (
            <li key={`${pointId}-${index}`} className="rounded-lg border border-border px-3 py-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <span>{index + 1}. {label}{city ? <span className="text-muted"> ({city})</span> : null}</span>
                  {isBranch ? <Chip size="sm" variant="secondary">{t('routes:branchBadge')}</Chip> : null}
                </div>
                <div className="flex gap-2">
                  <Button size="sm" variant="secondary" onPress={() => moveStop(index, -1)}>↑</Button>
                  <Button size="sm" variant="secondary" onPress={() => moveStop(index, 1)}>↓</Button>
                  <Button size="sm" variant="secondary" onPress={() => removeStop(index)}>{t('common:delete')}</Button>
                </div>
              </div>
              {index > 0 ? (
                <div className="mt-2 flex items-center gap-2">
                  <Label className="text-sm text-muted">{t('routes:legDuration')}</Label>
                  <NumberField
                    variant="secondary"
                    className="max-w-32"
                    minValue={1}
                    value={legDurations[index - 1] ?? 60}
                    onChange={(value) => updateLegDuration(index - 1, value)}
                  >
                    <NumberField.Group>
                      <NumberField.Input />
                    </NumberField.Group>
                  </NumberField>
                  <span className="text-sm text-muted">{t('routes:minutes')}</span>
                </div>
              ) : null}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
