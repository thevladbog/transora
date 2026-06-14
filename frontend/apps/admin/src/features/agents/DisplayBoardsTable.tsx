import { Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { AdminDisplayBoardResponse } from '@transora/api-client';
import { QueryState } from '@/components/ui/QueryState';
import { useStationDisplayBoards } from '@/features/stations/api/hooks';

export function DisplayBoardsTable({ stationId }: { stationId: string }) {
  const { t } = useTranslation('agents');
  const { data: boards, isLoading, isError } = useStationDisplayBoards(stationId);

  return (
    <section className="mt-6 rounded-xl border border-border bg-surface p-4">
      <h3 className="mb-3 font-medium">{t('displayBoards')}</h3>
      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (boards?.length ?? 0) === 0}
        errorMessage={t('displayBoardsLoadError')}
        emptyMessage={t('displayBoardsEmpty')}
      >
        <Table aria-label={t('displayBoards')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('boardName')}</Table.Column>
                <Table.Column>{t('boardType')}</Table.Column>
                <Table.Column>{t('platform')}</Table.Column>
                <Table.Column>{t('lastSeen')}</Table.Column>
              </Table.Header>
              <Table.Body items={boards ?? []}>
                {(item: AdminDisplayBoardResponse) => (
                  <Table.Row id={item.id}>
                    <Table.Cell>{item.name}</Table.Cell>
                    <Table.Cell className="font-mono">{item.boardType}</Table.Cell>
                    <Table.Cell>{item.platformNumber ?? '—'}</Table.Cell>
                    <Table.Cell className="text-sm text-muted">{item.lastSeenAt ?? '—'}</Table.Cell>
                  </Table.Row>
                )}
              </Table.Body>
            </Table.Content>
          </Table.ScrollContainer>
        </Table>
      </QueryState>
    </section>
  );
}
