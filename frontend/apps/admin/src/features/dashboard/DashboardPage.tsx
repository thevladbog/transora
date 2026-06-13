import { Chip } from '@heroui/react';
import { useAuth } from '../auth/auth-context';

export function DashboardPage() {
  const { user } = useAuth();

  if (!user) {
    return null;
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Dashboard</h1>
        <p className="text-muted">Добро пожаловать в панель управления Transora.</p>
      </div>

      <section className="rounded-xl border border-border bg-surface p-4">
        <h2 className="mb-3 text-lg font-medium">Профиль</h2>
        <dl className="grid gap-2 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-muted">Имя</dt>
            <dd>{user.fullName}</dd>
          </div>
          <div>
            <dt className="text-muted">Логин</dt>
            <dd>{user.login}</dd>
          </div>
          <div>
            <dt className="text-muted">Роль</dt>
            <dd>{user.isSuperuser ? 'System admin' : 'Station scoped'}</dd>
          </div>
          <div>
            <dt className="text-muted">Station ID</dt>
            <dd>{user.stationId ?? '—'}</dd>
          </div>
        </dl>
      </section>

      <section className="rounded-xl border border-border bg-surface p-4">
        <h2 className="mb-3 text-lg font-medium">Permissions ({user.permissions.length})</h2>
        <div className="flex flex-wrap gap-2">
          {user.permissions.map((permission) => (
            <Chip key={permission} size="sm" variant="secondary">
              {permission}
            </Chip>
          ))}
        </div>
      </section>
    </div>
  );
}
