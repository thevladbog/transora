import { Label, ListBox, Select } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { useStationContext } from '@/features/stations/station-context';

export function StationSwitcher() {
  const { t } = useTranslation('nav');
  const { stations, currentStationId, switchToStation } = useStationContext();

  if (stations.length <= 1) {
    if (stations.length === 1) {
      const station = stations[0];
      return (
        <div className="text-sm">
          <span className="font-mono text-muted">{station.code}</span>
          <span className="mx-1 text-muted">—</span>
          <span>{station.name}</span>
        </div>
      );
    }
    return null;
  }

  return (
    <div className="min-w-56">
      <Label className="sr-only">{t('switchStation')}</Label>
      <Select
        aria-label={t('switchStation')}
        selectedKey={currentStationId ?? undefined}
        onSelectionChange={(key) => {
          const id = key?.toString();
          if (id && id !== currentStationId) {
            void switchToStation(id);
          }
        }}
        variant="secondary"
        fullWidth
      >
        <Select.Trigger>
          <Select.Value />
          <Select.Indicator />
        </Select.Trigger>
        <Select.Popover>
          <ListBox>
            {stations.map((station) => (
              <ListBox.Item
                key={station.stationId}
                id={station.stationId}
                textValue={`${station.code} — ${station.name}`}
              >
                <span className="font-mono">{station.code}</span>
                <span className="mx-1 text-muted">—</span>
                <span>{station.name}</span>
              </ListBox.Item>
            ))}
          </ListBox>
        </Select.Popover>
      </Select>
    </div>
  );
}
