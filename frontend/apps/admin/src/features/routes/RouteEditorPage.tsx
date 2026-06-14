import { useMemo } from 'react';
import { Chip } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { TFunction } from 'i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { RoutePoliciesEditor } from '@/features/tariff-profiles/RoutePoliciesEditor';
import { TariffMatrixGrid } from '@/features/tariff-profiles/TariffMatrixGrid';
import { useRoutePricing, useUpsertRouteMatrix } from './api/hooks';
import { RouteMetaSection } from './RouteMetaSection';
import { RouteStopsEditor } from './RouteStopsEditor';

function formatDistance(km: number | undefined, source: string | undefined, t: TFunction<'routes'>): string {
  if (km == null) return t('routes:distanceUnavailable');
  const rounded = km.toFixed(1);
  if (source === 'ROAD') return t('routes:distanceByRoad', { km: rounded });
  if (source === 'STRAIGHT_LINE') return t('routes:distanceStraightLine', { km: rounded });
  return `${rounded} km`;
}

export function RouteEditorPage() {
  const { routeId = '' } = useParams();
  const navigate = useNavigate();
  const isNew = routeId === 'new';
  const { t } = useTranslation(['routes', 'common']);
  const { data: bundle, isLoading, isError, refetch } = useRoutePricing(isNew ? '' : routeId);
  const upsertMatrix = useUpsertRouteMatrix(isNew ? '' : routeId);

  const matrixStops = useMemo(
    () =>
      (bundle?.stops ?? []).map((stop) => ({
        id: stop.pointId,
        pointId: stop.pointId,
        stopOrder: stop.stopOrder,
        pointCode: stop.pointCode,
        pointName: stop.pointName,
        pointCity: stop.pointCity,
      })),
    [bundle?.stops],
  );

  const title = isNew
    ? t('routes:createTitle')
    : bundle
      ? `${bundle.routeNumber} — ${bundle.name}`
      : t('routes:editorTitle');

  return (
    <div className="space-y-6">
      <PageHeader
        title={title}
        description={isNew ? t('routes:editorDescription') : undefined}
        actions={
          <Link
            to="/routes"
            className="inline-flex items-center rounded-lg border border-border px-3 py-2 text-sm hover:bg-default"
          >
            {t('routes:backToList')}
          </Link>
        }
      />

      <RouteMetaSection
        mode={isNew ? 'create' : 'edit'}
        bundle={bundle}
        onCreated={(createdRouteId) => navigate(`/routes/${createdRouteId}`, { replace: true })}
        onUpdated={() => void refetch()}
      />

      {isNew ? (
        <>
          <section className="rounded-xl border border-dashed border-border p-4">
            <h2 className="mb-2 text-base font-semibold">{t('routes:stationsSection')}</h2>
            <p className="text-sm text-muted">{t('routes:saveMetaFirst')}</p>
          </section>
          <section className="rounded-xl border border-dashed border-border p-4">
            <h2 className="mb-2 text-base font-semibold">{t('routes:matrixSection')}</h2>
            <p className="text-sm text-muted">{t('routes:saveMetaFirst')}</p>
          </section>
          <section className="rounded-xl border border-dashed border-border p-4">
            <h2 className="mb-2 text-base font-semibold">{t('routes:policiesSection')}</h2>
            <p className="text-sm text-muted">{t('routes:saveMetaFirst')}</p>
          </section>
        </>
      ) : (
        <QueryState
          isLoading={isLoading}
          isError={isError}
          isEmpty={false}
          errorMessage={t('routes:loadError')}
          emptyMessage=""
        >
          {bundle ? (
            <>
              <section className="flex flex-wrap items-center gap-3 rounded-xl border border-border p-4">
                <h2 className="w-full text-base font-semibold">{t('routes:distanceKm')}</h2>
                <Chip variant="secondary">{formatDistance(bundle.distanceKm, bundle.distanceSource, t)}</Chip>
                {bundle.durationMin != null ? (
                  <Chip variant="secondary">{t('routes:durationMin', { min: bundle.durationMin })}</Chip>
                ) : null}
                {bundle.legs.length > 0 ? (
                  <ul className="flex flex-wrap gap-2 text-sm text-muted">
                    {bundle.legs.map((leg) => (
                      <li key={`${leg.fromStopOrder}-${leg.toStopOrder}`}>
                        {t('routes:legDistance', {
                          from: leg.fromStopOrder,
                          to: leg.toStopOrder,
                          km: leg.distanceKm.toFixed(1),
                        })}
                      </li>
                    ))}
                  </ul>
                ) : null}
              </section>
              <RouteStopsEditor routeId={routeId} stops={bundle.stops} onSaved={() => void refetch()} />
              <section className="rounded-xl border border-border p-4">
                <h2 className="mb-3 text-base font-semibold">{t('routes:matrixSection')}</h2>
                {matrixStops.length >= 2 ? (
                  <TariffMatrixGrid
                    stops={matrixStops}
                    initialCells={bundle.cells.map((cell) => ({
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
                ) : (
                  <p className="text-sm text-muted">{t('routes:needTwoStops')}</p>
                )}
              </section>
              <section className="rounded-xl border border-border p-4">
                <h2 className="mb-3 text-base font-semibold">{t('routes:policiesSection')}</h2>
                <RoutePoliciesEditor routeId={routeId} />
              </section>
            </>
          ) : null}
        </QueryState>
      )}
    </div>
  );
}
