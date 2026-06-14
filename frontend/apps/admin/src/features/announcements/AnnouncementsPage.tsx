import { useQuery } from '@tanstack/react-query';
import { Button, Chip, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { queue, pauseQueue, resumeQueue } from '@transora/api-client';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';

export function AnnouncementsPage() {
  const { t } = useTranslation('announcements');

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['announcements', 'queue'],
    queryFn: async () => {
      const response = await queue();
      return response.data;
    },
    refetchInterval: 15_000,
  });

  async function handlePause() {
    await pauseQueue();
    await refetch();
  }

  async function handleResume() {
    await resumeQueue();
    await refetch();
  }

  return (
    <div>
      <PageHeader
        title={t('title')}
        description={t('description')}
        actions={
          <div className="flex gap-2">
            <Button variant="secondary" size="sm" onPress={() => void handlePause()}>
              {t('pause')}
            </Button>
            <Button variant="secondary" size="sm" onPress={() => void handleResume()}>
              {t('resume')}
            </Button>
          </div>
        }
      />
      <div className="mb-4">
        <Chip size="sm" color={data?.queuePaused ? 'warning' : 'success'}>
          {data?.queuePaused ? t('paused') : t('active')}
        </Chip>
      </div>
      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (data?.items?.length ?? 0) === 0}
        errorMessage={t('loadError')}
        emptyMessage={t('empty')}
      >
        <Table aria-label={t('title')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('text')}</Table.Column>
                <Table.Column>{t('priority')}</Table.Column>
                <Table.Column>{t('status')}</Table.Column>
              </Table.Header>
              <Table.Body items={data?.items ?? []}>
                {(item) => (
                  <Table.Row id={item.id}>
                    <Table.Cell>{item.textContent}</Table.Cell>
                    <Table.Cell className="font-mono">{item.priority}</Table.Cell>
                    <Table.Cell className="font-mono">{item.status}</Table.Cell>
                  </Table.Row>
                )}
              </Table.Body>
            </Table.Content>
          </Table.ScrollContainer>
        </Table>
      </QueryState>
    </div>
  );
}
