import { useMemo, useState } from 'react';
import { Button, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import type { TariffProfileResponse } from '@transora/api-client';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { StatusChip } from '@/components/ui/StatusChip';
import { useDeleteTariffProfile, useTariffProfilesList } from './api/hooks';
import { formatRouteLabel, useRoutesList } from './api/routes-hooks';
import { TariffProfileFormDrawer } from './TariffProfileFormDrawer';

export function TariffProfilesListPage() {
  const { t } = useTranslation(['tariffProfiles', 'common']);
  const navigate = useNavigate();
  const { data: profiles, isLoading, isError } = useTariffProfilesList();
  const { data: routes } = useRoutesList();
  const deleteProfile = useDeleteTariffProfile();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<TariffProfileResponse | null>(null);
  const [deleting, setDeleting] = useState<TariffProfileResponse | null>(null);

  const routeLabels = useMemo(() => {
    const map = new Map<string, string>();
    for (const route of routes ?? []) {
      map.set(route.id, formatRouteLabel(route));
    }
    return map;
  }, [routes]);

  function routeCell(profile: TariffProfileResponse) {
    if (!profile.routeId) return '—';
    return routeLabels.get(profile.routeId) ?? (
      <span className="font-mono text-sm">{profile.routeId}</span>
    );
  }

  return (
    <div>
      <PageHeader
        title={t('tariffProfiles:title')}
        description={t('tariffProfiles:description')}
        actions={
          <Button variant="primary" onPress={() => { setEditing(null); setDrawerOpen(true); }}>
            {t('tariffProfiles:create')}
          </Button>
        }
      />
      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (profiles?.length ?? 0) === 0}
        errorMessage={t('tariffProfiles:loadError')}
        emptyMessage={t('tariffProfiles:empty')}
      >
        <Table aria-label={t('tariffProfiles:title')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('tariffProfiles:name')}</Table.Column>
                <Table.Column>{t('tariffProfiles:stopsCount')}</Table.Column>
                <Table.Column>{t('tariffProfiles:route')}</Table.Column>
                <Table.Column>{t('tariffProfiles:status')}</Table.Column>
                <Table.Column>{t('common:actions')}</Table.Column>
              </Table.Header>
              <Table.Body items={profiles ?? []}>
                {(item: TariffProfileResponse) => (
                  <Table.Row id={item.id}>
                    <Table.Cell>{item.name}</Table.Cell>
                    <Table.Cell className="font-mono">{item.stopCount}</Table.Cell>
                    <Table.Cell>{routeCell(item)}</Table.Cell>
                    <Table.Cell><StatusChip active={item.isActive} /></Table.Cell>
                    <Table.Cell>
                      <div className="flex flex-wrap gap-2">
                        <Button size="sm" variant="primary" onPress={() => navigate(`/tariff-profiles/${item.id}`)}>
                          {t('tariffProfiles:editMatrix')}
                        </Button>
                        <Button size="sm" variant="secondary" onPress={() => { setEditing(item); setDrawerOpen(true); }}>
                          {t('tariffProfiles:settings')}
                        </Button>
                        <Button size="sm" variant="secondary" onPress={() => setDeleting(item)}>
                          {t('common:delete')}
                        </Button>
                      </div>
                    </Table.Cell>
                  </Table.Row>
                )}
              </Table.Body>
            </Table.Content>
          </Table.ScrollContainer>
        </Table>
      </QueryState>
      <TariffProfileFormDrawer
        isOpen={drawerOpen}
        profile={editing}
        onOpenChange={(open) => { setDrawerOpen(open); if (!open) setEditing(null); }}
        onSaved={(saved) => { if (!editing) navigate(`/tariff-profiles/${saved.id}`); }}
      />
      <ConfirmDialog
        isOpen={deleting !== null}
        title={t('common:confirm')}
        message={deleting ? t('tariffProfiles:deleteConfirm', { name: deleting.name }) : ''}
        confirmLabel={t('common:delete')}
        isPending={deleteProfile.isPending}
        onConfirm={() => { if (deleting) void deleteProfile.mutateAsync(deleting.id).then(() => setDeleting(null)); }}
        onOpenChange={(open) => { if (!open) setDeleting(null); }}
      />
    </div>
  );
}
