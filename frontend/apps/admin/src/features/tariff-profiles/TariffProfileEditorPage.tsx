import { useMemo, useState } from 'react';
import { Button, ListBox, Select } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { usePointsList } from '@/features/points/api/hooks';
import {
  useReplaceTariffStops,
  useTariffMatrix,
  useUpsertTariffMatrix,
} from './api/hooks';
import { RoutePoliciesEditor } from './RoutePoliciesEditor';
import { TariffMatrixGrid } from './TariffMatrixGrid';
import { TariffProfileFormDrawer } from './TariffProfileFormDrawer';

export function TariffProfileEditorPage() {
  const { profileId = '' } = useParams();
  const { t } = useTranslation(['tariffProfiles', 'common']);
  const { data: matrix, isLoading, isError, refetch } = useTariffMatrix(profileId);
  const { data: points } = usePointsList();
  const replaceStops = useReplaceTariffStops(profileId);
  const upsertMatrix = useUpsertTariffMatrix(profileId);
  const [selectedPointId, setSelectedPointId] = useState<string>('');
  const [settingsOpen, setSettingsOpen] = useState(false);

  const stopPointIds = useMemo(
    () => (matrix?.stops ?? []).map((stop) => stop.pointId),
    [matrix?.stops],
  );

  async function addStop() {
    if (!selectedPointId || stopPointIds.includes(selectedPointId)) return;
    await replaceStops.mutateAsync([...stopPointIds, selectedPointId]);
    setSelectedPointId('');
  }

  async function moveStop(index: number, direction: -1 | 1) {
    const next = [...stopPointIds];
    const target = index + direction;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    await replaceStops.mutateAsync(next);
  }

  async function removeStop(index: number) {
    const next = stopPointIds.filter((_, i) => i !== index);
    await replaceStops.mutateAsync(next);
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title={matrix?.profile.name ?? t('tariffProfiles:editorTitle')}
        description={t('tariffProfiles:editorDescription')}
        actions={
          <div className="flex gap-2">
            <Button variant="secondary" onPress={() => setSettingsOpen(true)}>
              {t('tariffProfiles:settings')}
            </Button>
            <Link
              to="/tariff-profiles"
              className="inline-flex items-center rounded-lg border border-border px-3 py-2 text-sm hover:bg-default"
            >
              {t('tariffProfiles:backToList')}
            </Link>
          </div>
        }
      />
      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={false}
        errorMessage={t('tariffProfiles:loadError')}
        emptyMessage=""
      >
        <section className="space-y-3 rounded-xl border border-border p-4">
          <h2 className="text-base font-semibold">{t('tariffProfiles:stopsSection')}</h2>
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
            <Button variant="primary" isPending={replaceStops.isPending} onPress={() => void addStop()}>
              {t('tariffProfiles:addStop')}
            </Button>
          </div>
          <ol className="space-y-2">
            {(matrix?.stops ?? []).map((stop, index) => (
              <li key={stop.id} className="flex items-center justify-between rounded-lg border border-border px-3 py-2">
                <span>{index + 1}. {stop.pointName} <span className="text-muted">({stop.pointCity})</span></span>
                <div className="flex gap-2">
                  <Button size="sm" variant="secondary" onPress={() => void moveStop(index, -1)}>↑</Button>
                  <Button size="sm" variant="secondary" onPress={() => void moveStop(index, 1)}>↓</Button>
                  <Button size="sm" variant="secondary" onPress={() => void removeStop(index)}>{t('common:delete')}</Button>
                </div>
              </li>
            ))}
          </ol>
        </section>
        <RoutePoliciesEditor routeId={matrix?.profile.routeId} />
        <section className="rounded-xl border border-border p-4">
          <h2 className="mb-3 text-base font-semibold">{t('tariffProfiles:matrixSection')}</h2>
          {matrix ? (
            <TariffMatrixGrid
              stops={matrix.stops}
              initialCells={matrix.cells.map((cell) => ({
                fromStopOrder: cell.fromStopOrder,
                toStopOrder: cell.toStopOrder,
                priceCents: cell.priceCents,
                isMirrorOverride: cell.isMirrorOverride,
              }))}
              isPending={upsertMatrix.isPending}
              onSave={async (cells) => {
                await upsertMatrix.mutateAsync(cells);
                await refetch();
              }}
            />
          ) : null}
        </section>
      </QueryState>
      <TariffProfileFormDrawer
        isOpen={settingsOpen}
        profile={matrix?.profile ?? null}
        onOpenChange={setSettingsOpen}
        onSaved={() => void refetch()}
      />
    </div>
  );
}
