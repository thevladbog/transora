import { useState } from 'react';
import { Link, useParams } from 'react-router';
import {
  Alert,
  Button,
  Chip,
  Input,
  Label,
  Modal,
  TextField,
  useOverlayState,
} from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { z } from 'zod';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueryState } from '@/components/ui/QueryState';
import { StatusChip, SuperuserChip } from '@/components/ui/StatusChip';
import { PermissionGate } from '@/components/layout/PermissionGate';
import { Permissions } from '@/lib/permissions';
import {
  useActivateUser,
  useChangeUserPassword,
  useDeactivateUser,
  useRevokeUserAssignment,
  useUser,
} from './api/hooks';

type ConfirmAction = 'deactivate' | 'activate' | 'revoke';

export function UserDetailPage() {
  const { userId } = useParams<{ userId: string }>();
  const { t } = useTranslation(['users', 'common']);
  const { data: user, isLoading, isError } = useUser(userId);

  const deactivateUser = useDeactivateUser();
  const activateUser = useActivateUser();
  const changePassword = useChangeUserPassword();
  const revokeAssignment = useRevokeUserAssignment();

  const [confirmAction, setConfirmAction] = useState<ConfirmAction | null>(null);
  const [revokeTarget, setRevokeTarget] = useState<{ assignmentId: string; role: string; stationId: string } | null>(null);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [newPassword, setNewPassword] = useState('');
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const passwordModal = useOverlayState({ isOpen: passwordOpen, onOpenChange: setPasswordOpen });

  const passwordSchema = z.object({
    newPassword: z.string().min(1, t('users:passwordRequired')),
  });

  async function handleConfirm() {
    if (!userId || !user) {
      return;
    }
    setActionError(null);

    try {
      if (confirmAction === 'deactivate') {
        await deactivateUser.mutateAsync(userId);
      } else if (confirmAction === 'activate') {
        await activateUser.mutateAsync(userId);
      } else if (confirmAction === 'revoke' && revokeTarget) {
        await revokeAssignment.mutateAsync({
          userId,
          assignmentId: revokeTarget.assignmentId,
        });
      }
      setConfirmAction(null);
      setRevokeTarget(null);
    } catch {
      setActionError(t('users:actionError'));
    }
  }

  async function handleChangePassword(event: React.FormEvent) {
    event.preventDefault();
    if (!userId) {
      return;
    }
    setPasswordError(null);

    const parsed = passwordSchema.safeParse({ newPassword });
    if (!parsed.success) {
      setPasswordError(parsed.error.issues[0]?.message ?? t('common:errorGeneric'));
      return;
    }

    try {
      await changePassword.mutateAsync({
        userId,
        payload: { newPassword: parsed.data.newPassword },
      });
      setNewPassword('');
      passwordModal.close();
    } catch {
      setPasswordError(t('users:passwordChangeError'));
    }
  }

  const confirmMessage =
    confirmAction === 'deactivate'
      ? t('users:deactivateConfirm', { login: user?.login ?? '' })
      : confirmAction === 'activate'
        ? t('users:activateConfirm', { login: user?.login ?? '' })
        : confirmAction === 'revoke' && revokeTarget
          ? t('users:revokeConfirm', {
              role: revokeTarget.role,
              stationId: revokeTarget.stationId,
            })
          : '';

  const isConfirmPending =
    deactivateUser.isPending || activateUser.isPending || revokeAssignment.isPending;

  return (
    <div>
      <div className="mb-4">
        <Link
          to="/users"
          className="inline-flex items-center rounded-lg border border-border px-3 py-1.5 text-sm hover:bg-default"
        >
          {t('users:backToList')}
        </Link>
      </div>

      <QueryState
        isLoading={isLoading}
        isError={isError || !user}
        errorMessage={t('users:notFound')}
      >
        {user ? (
          <div className="space-y-6">
            <PageHeader
              title={user.fullName}
              description={<span className="font-mono text-sm text-muted">{user.login}</span>}
              actions={
                <div className="flex flex-wrap gap-2">
                  <StatusChip active={user.isActive} />
                  <SuperuserChip show={user.isSuperuser} />
                </div>
              }
            />

            {actionError ? (
              <Alert status="danger">
                <Alert.Indicator />
                <Alert.Content>
                  <Alert.Description>{actionError}</Alert.Description>
                </Alert.Content>
              </Alert>
            ) : null}

            <section className="rounded-xl border border-border bg-surface p-4">
              <h2 className="mb-3 text-lg font-medium">{t('users:profile')}</h2>
              <dl className="grid gap-3 text-sm sm:grid-cols-2">
                <div>
                  <dt className="text-muted">{t('users:login')}</dt>
                  <dd className="font-mono">{user.login}</dd>
                </div>
                <div>
                  <dt className="text-muted">{t('users:email')}</dt>
                  <dd>{user.email ?? '—'}</dd>
                </div>
                <div>
                  <dt className="text-muted">{t('users:userTypeLabel')}</dt>
                  <dd className="font-mono">{user.userType}</dd>
                </div>
                <div>
                  <dt className="text-muted">{t('users:status')}</dt>
                  <dd>
                    <StatusChip active={user.isActive} />
                  </dd>
                </div>
              </dl>
            </section>

            <section className="rounded-xl border border-border bg-surface p-4">
              <h2 className="mb-3 text-lg font-medium">{t('users:assignments')}</h2>
              {user.assignments.length === 0 ? (
                <p className="text-sm text-muted">{t('users:noAssignments')}</p>
              ) : (
                <div className="space-y-3">
                  {user.assignments.map((assignment) => (
                    <div
                      key={assignment.assignmentId}
                      className="flex flex-wrap items-start justify-between gap-3 rounded-lg border border-border p-3"
                    >
                      <div className="space-y-1 text-sm">
                        <div>
                          <span className="text-muted">{t('users:assignmentRole')}: </span>
                          <span className="font-mono">{assignment.roleCode}</span>
                        </div>
                        <div>
                          <span className="text-muted">{t('users:assignmentStation')}: </span>
                          <span className="font-mono">{assignment.stationId}</span>
                        </div>
                        <div className="flex flex-wrap gap-1 pt-1">
                          {assignment.permissions.map((permission) => (
                            <Chip key={permission} size="sm" variant="secondary" className="font-mono text-xs">
                              {permission}
                            </Chip>
                          ))}
                        </div>
                      </div>
                      <PermissionGate permission={Permissions.USERS_EDIT}>
                        <Button
                          size="sm"
                          variant="secondary"
                          onPress={() => {
                            setRevokeTarget({
                              assignmentId: assignment.assignmentId,
                              role: assignment.roleCode,
                              stationId: assignment.stationId,
                            });
                            setConfirmAction('revoke');
                          }}
                        >
                          {t('users:revokeAssignment')}
                        </Button>
                      </PermissionGate>
                    </div>
                  ))}
                </div>
              )}
            </section>

            <section className="rounded-xl border border-border bg-surface p-4">
              <h2 className="mb-3 text-lg font-medium">{t('users:actions')}</h2>
              <div className="flex flex-wrap gap-2">
                <PermissionGate permission={Permissions.USERS_EDIT}>
                  <Button variant="secondary" onPress={() => setPasswordOpen(true)}>
                    {t('users:changePassword')}
                  </Button>
                </PermissionGate>
                <PermissionGate permission={Permissions.USERS_DEACTIVATE}>
                  {user.isActive ? (
                    <Button variant="secondary" onPress={() => setConfirmAction('deactivate')}>
                      {t('users:deactivate')}
                    </Button>
                  ) : (
                    <Button variant="primary" onPress={() => setConfirmAction('activate')}>
                      {t('users:activate')}
                    </Button>
                  )}
                </PermissionGate>
              </div>
            </section>
          </div>
        ) : null}
      </QueryState>

      <ConfirmDialog
        isOpen={confirmAction !== null}
        title={t('common:confirm')}
        message={confirmMessage}
        isPending={isConfirmPending}
        onConfirm={() => void handleConfirm()}
        onOpenChange={(open) => {
          if (!open) {
            setConfirmAction(null);
            setRevokeTarget(null);
          }
        }}
      />

      <Modal state={passwordModal}>
        <Modal.Backdrop>
          <Modal.Container size="sm">
            <Modal.Dialog>
              <Modal.Header>
                <Modal.Heading>{t('users:changePasswordTitle')}</Modal.Heading>
              </Modal.Header>
              <Modal.Body>
                <form className="space-y-4" id="change-password-form" onSubmit={handleChangePassword}>
                  <TextField
                    isRequired
                    name="newPassword"
                    type="password"
                    value={newPassword}
                    onChange={setNewPassword}
                  >
                    <Label>{t('users:newPassword')}</Label>
                    <Input type="password" />
                  </TextField>
                  {passwordError ? (
                    <Alert status="danger">
                      <Alert.Indicator />
                      <Alert.Content>
                        <Alert.Description>{passwordError}</Alert.Description>
                      </Alert.Content>
                    </Alert>
                  ) : null}
                </form>
              </Modal.Body>
              <Modal.Footer>
                <Button variant="secondary" onPress={() => passwordModal.close()}>
                  {t('common:cancel')}
                </Button>
                <Button
                  form="change-password-form"
                  type="submit"
                  variant="primary"
                  isPending={changePassword.isPending}
                >
                  {t('common:save')}
                </Button>
              </Modal.Footer>
            </Modal.Dialog>
          </Modal.Container>
        </Modal.Backdrop>
      </Modal>
    </div>
  );
}
