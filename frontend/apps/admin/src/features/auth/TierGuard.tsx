import { Navigate, Outlet } from 'react-router';
import { Spinner } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { useAuth } from './auth-context';
import { useStationContext } from '@/features/stations/station-context';
import { isNetworkTier, isStationTier } from '@/lib/app-tier';

export function NetworkTierGuard() {
  if (isStationTier()) {
    return <Navigate to="/" replace />;
  }
  return <Outlet />;
}

export function StationTierGuard() {
  const { user } = useAuth();
  const { stations, isLoading } = useStationContext();
  const { t } = useTranslation('common');

  if (isNetworkTier()) {
    return <Navigate to="/" replace />;
  }

  if (isLoading) {
    return (
      <div className="flex min-h-[40vh] items-center justify-center">
        <Spinner size="lg" />
      </div>
    );
  }

  if (user && !user.isSuperuser && stations.length === 0) {
    return (
      <div className="rounded-xl border border-border bg-surface p-8 text-center">
        <h2 className="text-lg font-medium">{t('noStationAccessTitle')}</h2>
        <p className="mt-2 text-muted">{t('noStationAccessDescription')}</p>
      </div>
    );
  }

  return <Outlet />;
}
