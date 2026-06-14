import { useTranslation } from 'react-i18next';
import { PageHeader } from '@/components/ui/PageHeader';
import { useStationAgentStatus } from '@/features/stations/api/hooks';
import { useStationContext } from '@/features/stations/station-context';

export function StationSettingsPage() {
  const { t } = useTranslation('settings');
  const { currentStationId, currentStation } = useStationContext();
  const { data: agentStatus } = useStationAgentStatus(currentStationId ?? undefined, 15_000);

  if (!currentStation) {
    return null;
  }

  return (
    <div>
      <PageHeader title={t('title')} description={t('description')} />
      <dl className="grid max-w-2xl gap-3 rounded-xl border border-border bg-surface p-4 text-sm sm:grid-cols-2">
        <div>
          <dt className="text-muted">{t('code')}</dt>
          <dd className="font-mono">{currentStation.code}</dd>
        </div>
        <div>
          <dt className="text-muted">{t('name')}</dt>
          <dd>{currentStation.name}</dd>
        </div>
        <div>
          <dt className="text-muted">{t('city')}</dt>
          <dd>{currentStation.city}</dd>
        </div>
        <div>
          <dt className="text-muted">{t('agentStatus')}</dt>
          <dd>{agentStatus?.connected ? t('agentOnline') : t('agentOffline')}</dd>
        </div>
        <div className="sm:col-span-2 text-xs text-muted">{t('readOnlyHint')}</div>
      </dl>
    </div>
  );
}
