import { useState } from 'react';
import { useNavigate } from 'react-router';
import { Button, Table } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import type { UserSummaryResponse } from '@transora/api-client';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { StatusChip, SuperuserChip } from '@/components/ui/StatusChip';
import { PermissionGate } from '@/components/layout/PermissionGate';
import { Permissions } from '@/lib/permissions';
import { useUsersList } from './api/hooks';
import { UserCreateModal } from './UserCreateModal';

export function UsersListPage() {
  const { t } = useTranslation(['users', 'common']);
  const navigate = useNavigate();
  const { data: users, isLoading, isError } = useUsersList();
  const [createOpen, setCreateOpen] = useState(false);

  return (
    <div>
      <PageHeader
        title={t('users:title')}
        description={t('users:description')}
        actions={
          <PermissionGate permission={Permissions.USERS_CREATE}>
            <Button variant="primary" onPress={() => setCreateOpen(true)}>
              {t('users:createUser')}
            </Button>
          </PermissionGate>
        }
      />

      <QueryState
        isLoading={isLoading}
        isError={isError}
        isEmpty={!isLoading && !isError && (users?.length ?? 0) === 0}
        errorMessage={t('users:loadError')}
        emptyMessage={t('users:empty')}
      >
        <Table aria-label={t('users:title')} variant="secondary">
          <Table.ScrollContainer>
            <Table.Content>
              <Table.Header>
                <Table.Column isRowHeader>{t('users:login')}</Table.Column>
                <Table.Column>{t('users:fullName')}</Table.Column>
                <Table.Column>{t('users:status')}</Table.Column>
                <Table.Column>{t('users:userType')}</Table.Column>
                <Table.Column>{t('common:actions')}</Table.Column>
              </Table.Header>
              <Table.Body items={users ?? []}>
                {(item: UserSummaryResponse) => (
                  <Table.Row
                    id={item.userId}
                    className="cursor-pointer"
                    onAction={() => navigate(`/users/${item.userId}`)}
                  >
                    <Table.Cell className="font-mono">{item.login}</Table.Cell>
                    <Table.Cell>{item.fullName}</Table.Cell>
                    <Table.Cell>
                      <div className="flex flex-wrap gap-1">
                        <StatusChip active={item.isActive} />
                        <SuperuserChip show={item.isSuperuser} />
                      </div>
                    </Table.Cell>
                    <Table.Cell>{item.isSuperuser ? t('users:superuser') : '—'}</Table.Cell>
                    <Table.Cell>
                      <Button
                        size="sm"
                        variant="secondary"
                        onPress={() => navigate(`/users/${item.userId}`)}
                      >
                        {t('common:view')}
                      </Button>
                    </Table.Cell>
                  </Table.Row>
                )}
              </Table.Body>
            </Table.Content>
          </Table.ScrollContainer>
        </Table>
      </QueryState>

      <UserCreateModal
        isOpen={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(userId) => navigate(`/users/${userId}`)}
      />
    </div>
  );
}
