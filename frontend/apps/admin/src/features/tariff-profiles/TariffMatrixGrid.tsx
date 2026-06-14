import { useMemo, useState } from 'react';
import { Button, Input } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { TariffCellRequest, TariffProfileStopResponse } from '@transora/api-client';

type CellKey = `${number}-${number}`;

function cellKey(from: number, to: number): CellKey {
  return `${from}-${to}`;
}

type TariffMatrixGridProps = {
  stops: TariffProfileStopResponse[];
  initialCells: TariffCellRequest[];
  onSave: (cells: TariffCellRequest[]) => Promise<void>;
  isPending?: boolean;
};

export function TariffMatrixGrid({ stops, initialCells, onSave, isPending }: TariffMatrixGridProps) {
  const { t } = useTranslation(['tariffProfiles', 'common']);

  const initialMap = useMemo(() => {
    const map = new Map<CellKey, { price: string; override: boolean }>();
    initialCells.forEach((cell) => {
      map.set(cellKey(cell.fromStopOrder, cell.toStopOrder), {
        price: String(cell.priceCents),
        override: cell.isMirrorOverride ?? false,
      });
    });
    return map;
  }, [initialCells]);

  const [cells, setCells] = useState(initialMap);
  const [overrides, setOverrides] = useState<Set<CellKey>>(() => {
    const set = new Set<CellKey>();
    initialCells.forEach((cell) => {
      if (cell.isMirrorOverride) set.add(cellKey(cell.fromStopOrder, cell.toStopOrder));
    });
    return set;
  });

  function getPrice(from: number, to: number): string {
    return cells.get(cellKey(from, to))?.price ?? '';
  }

  function setPrice(from: number, to: number, price: string, markOverride = false) {
    const key = cellKey(from, to);
    setCells((prev) => {
      const next = new Map(prev);
      next.set(key, { price, override: markOverride || overrides.has(key) });
      if (from !== to) {
        const mirrorKey = cellKey(to, from);
        if (!overrides.has(mirrorKey) && !markOverride) {
          next.set(mirrorKey, { price, override: false });
        }
      }
      return next;
    });
    if (markOverride) {
      setOverrides((prev) => new Set(prev).add(key));
    }
  }

  function resetMirror(from: number, to: number) {
    const mirrorKey = cellKey(to, from);
    setOverrides((prev) => {
      const next = new Set(prev);
      next.delete(mirrorKey);
      return next;
    });
    const source = getPrice(from, to);
    if (source) setPrice(to, from, source, false);
  }

  async function handleSave() {
    const payload: TariffCellRequest[] = [];
    cells.forEach((value, key) => {
      if (!value.price) return;
      const [from, to] = key.split('-').map(Number);
      if (to <= from) return;
      payload.push({
        fromStopOrder: from,
        toStopOrder: to,
        priceCents: Number(value.price),
        isMirrorOverride: overrides.has(key),
      });
    });
    await onSave(payload);
  }

  if (stops.length < 2) {
    return <p className="text-sm text-muted">{t('tariffProfiles:needTwoStops')}</p>;
  }

  return (
    <div className="space-y-4">
      <div className="overflow-x-auto">
        <table className="min-w-full border-collapse text-sm">
          <thead>
            <tr>
              <th className="border border-border bg-default p-2" />
              {stops.map((stop) => (
                <th key={stop.id} className="border border-border bg-default p-2 font-medium">
                  {stop.pointName ?? stop.pointCode}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {stops.map((fromStop) => (
              <tr key={fromStop.id}>
                <th className="border border-border bg-default p-2 text-left font-medium">
                  {fromStop.pointName ?? fromStop.pointCode}
                </th>
                {stops.map((toStop) => {
                  const disabled = toStop.stopOrder === fromStop.stopOrder;
                  const isMirror = toStop.stopOrder < fromStop.stopOrder;
                  const override = overrides.has(cellKey(fromStop.stopOrder, toStop.stopOrder));
                  return (
                    <td key={`${fromStop.id}-${toStop.id}`} className="border border-border p-1">
                      {disabled ? (
                        <span className="block px-2 py-1 text-center text-muted">—</span>
                      ) : (
                        <div className="space-y-1">
                          <Input
                            aria-label={`${fromStop.stopOrder}-${toStop.stopOrder}`}
                            type="number"
                            min={0}
                            value={getPrice(fromStop.stopOrder, toStop.stopOrder)}
                            onChange={(value) =>
                              setPrice(fromStop.stopOrder, toStop.stopOrder, String(value), isMirror)
                            }
                            className="font-mono"
                          />
                          {isMirror && override ? (
                            <Button
                              size="sm"
                              variant="secondary"
                              onPress={() => resetMirror(toStop.stopOrder, fromStop.stopOrder)}
                            >
                              {t('tariffProfiles:resetMirror')}
                            </Button>
                          ) : null}
                          {isMirror && !override ? (
                            <span className="text-xs text-muted">{t('tariffProfiles:autoMirror')}</span>
                          ) : null}
                        </div>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <Button variant="primary" isPending={isPending} onPress={() => void handleSave()}>
        {t('tariffProfiles:saveMatrix')}
      </Button>
    </div>
  );
}
