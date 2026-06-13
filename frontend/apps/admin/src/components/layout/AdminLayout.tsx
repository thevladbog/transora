import { NavLink, Outlet } from 'react-router';
import { Button, Chip } from '@heroui/react';
import { useAuth } from '../../features/auth/auth-context';
import { PermissionGate } from './PermissionGate';
import { Permissions } from '../../lib/permissions';

type NavItem = {
  to: string;
  label: string;
  permission?: (typeof Permissions)[keyof typeof Permissions];
  superuser?: boolean;
};

const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Dashboard' },
  { to: '/users', label: 'Пользователи', permission: Permissions.USERS_VIEW },
  { to: '/service-tokens', label: 'Service tokens', superuser: true },
  { to: '/tariffs', label: 'Тарифы', permission: Permissions.SETTINGS_MANAGE_TARIFFS },
  { to: '/refund-policies', label: 'Возвраты', permission: Permissions.SETTINGS_MANAGE_TARIFFS },
  { to: '/audit', label: 'Audit log', permission: Permissions.USERS_VIEW },
  { to: '/reports', label: 'Отчёты', permission: Permissions.REPORTS_VIEW_STATION },
];

function SidebarNav() {
  return (
    <nav className="flex flex-col gap-1 p-3">
      {NAV_ITEMS.map((item) => (
        <PermissionGate
          key={item.to}
          permission={item.permission}
          superuser={item.superuser}
        >
          <NavLink
            to={item.to}
            end={item.to === '/'}
            className={({ isActive }) =>
              [
                'rounded-lg px-3 py-2 text-sm transition-colors',
                isActive
                  ? 'bg-accent text-accent-foreground font-medium'
                  : 'text-foreground hover:bg-default',
              ].join(' ')
            }
          >
            {item.label}
          </NavLink>
        </PermissionGate>
      ))}
    </nav>
  );
}

export function AdminLayout() {
  const { user, logoutUser } = useAuth();

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      <aside className="flex w-64 shrink-0 flex-col border-r border-border bg-surface">
        <div className="border-b border-border p-4">
          <div className="text-lg font-semibold">Transora</div>
          <div className="text-xs text-muted">Admin</div>
        </div>
        <SidebarNav />
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between border-b border-border px-6 py-3">
          <div>
            <div className="font-medium">{user?.fullName}</div>
            <div className="text-sm text-muted">{user?.login}</div>
          </div>
          <div className="flex items-center gap-3">
            {user?.isSuperuser ? <Chip size="sm">Superuser</Chip> : null}
            <Button variant="secondary" onPress={() => void logoutUser()}>
              Выйти
            </Button>
          </div>
        </header>
        <main className="flex-1 overflow-auto p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
