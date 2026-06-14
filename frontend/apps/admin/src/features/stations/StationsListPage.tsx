import { useState } from 'react';
import { Alert, Button, Chip, Modal, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { AdminStationResponse } from '@transora/api-client';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { StatusChip } from '@/components/ui/StatusChip';
import {
  useCreateProvisioningToken,
  useStationsList,
} from './api/hooks';
import { StationFormDrawer } from './StationFormDrawer';

export function StationsListPage() {
  const { t } = useTranslation(['stations', 'common']);
  const { data: stations, isLoading, isError } = useStationsList();
  const createToken = useCreateProvisioningToken();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<AdminStationResponse | null>(null);
  const [provisionCode, setProvisionCode] = useState<{ code: string; expiresAt: string } | null>(null);

  async function handleGenerateCode(station: AdminStationResponse) {
    const result = await createToken.mutateAsync(station.id);
    setProvisionCode(result);
  }

  return (
    <div>
      <PageHeader
        title={t('stations:title')}
        description={t('stations:description')}
        actions={
          <Button variant="primary" onPress={() => { setEditing(null); setDrawerOpen(true); }}>
            {t('stations:create')}
          </Button>
        }
      />
      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (stations?.length ?? 0) === 0}
        errorMessage={t('stations:loadError')}
        emptyMessage={t('stations:empty')}
      >
        <Table aria-label={t('stations:title')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('stations:code')}</Table.Column>
                <Table.Column>{t('stations:name')}</Table.Column>
                <Table.Column>{t('stations:city')}</Table.Column>
                <Table.Column>{t('stations:agentStatus')}</Table.Column>
                <Table.Column>{t('stations:status')}</Table.Column>
                <Table.Column>{t('common:actions')}</Table.Column>
              </Table.Header>
              <Table.Body items={stations ?? []}>
                {(item: AdminStationResponse) => (
                  <Table.Row id={item.id}>
                    <Table.Cell className="font-mono">{item.code}</Table.Cell>
                    <Table.Cell>{item.name}</Table.Cell>
                    <Table.Cell>{item.city}</Table.Cell>
                    <Table.Cell>
                      <Chip
                        size="sm"
                        color={item.agentConnected ? 'success' : 'default'}
                        variant={item.agentConnected ? 'primary' : 'secondary'}
                      >
                        {item.agentConnected ? t('stations:agentOnline') : t('stations:agentOffline')}
                      </Chip>
                      {item.agentLastSeenAt ? (
                        <div className="mt-1 text-xs text-muted">{item.agentLastSeenAt}</div>
                      ) : null}
                    </Table.Cell>
                    <Table.Cell>
                      <StatusChip active={item.isActive} />
                    </Table.Cell>
                    <Table.Cell>
                      <div className="flex flex-wrap gap-2">
                        <Button
                          size="sm"
                          variant="secondary"
                          onPress={() => { setEditing(item); setDrawerOpen(true); }}
                        >
                          {t('stations:edit')}
                        </Button>
                        <Button
                          size="sm"
                          variant="secondary"
                          isDisabled={createToken.isPending}
                          onPress={() => void handleGenerateCode(item)}
                        >
                          {t('stations:generateCode')}
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

      <StationFormDrawer
        isOpen={drawerOpen}
        station={editing}
        onOpenChange={(open) => { setDrawerOpen(open); if (!open) setEditing(null); }}
      />

      <Modal isOpen={provisionCode !== null} onOpenChange={(open) => { if (!open) setProvisionCode(null); }}>
        <Modal.Backdrop>
          <Modal.Container size="md">
            <Modal.Dialog>
              <Modal.Header>
                <Modal.Heading>{t('stations:provisionCodeTitle')}</Modal.Heading>
              </Modal.Header>
              <Modal.Body className="space-y-3">
                <Alert status="warning">{t('stations:provisionCodeHint')}</Alert>
                {provisionCode ? (
                  <>
                    <div className="rounded-lg bg-default px-4 py-3 font-mono text-lg tracking-widest">
                      {provisionCode.code}
                    </div>
                    <p className="text-sm text-muted">
                      {t('stations:provisionCodeExpires', { date: provisionCode.expiresAt })}
                    </p>
                  </>
                ) : null}
              </Modal.Body>
              <Modal.Footer>
                <Button variant="secondary" onPress={() => setProvisionCode(null)}>
                  {t('common:close')}
                </Button>
              </Modal.Footer>
            </Modal.Dialog>
          </Modal.Container>
        </Modal.Backdrop>
      </Modal>
    </div>
  );
}
