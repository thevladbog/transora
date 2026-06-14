import { NavLink, Outlet } from 'react-router';
import { Button, Chip } from '@heroui/react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '@/features/auth/auth-context';
import { Permissions } from '@/lib/permissions';
import { isNetworkTier, isStationTier } from '@/lib/app-tier';
import { LocaleThemeToolbar } from './LocaleThemeToolbar';
import { PermissionGate } from './PermissionGate';
import { StationSwitcher } from './StationSwitcher';

type NavItem = {
  to: string;
  labelKey: string;
  permission?: (typeof Permissions)[keyof typeof Permissions];
  superuser?: boolean;
  networkOnly?: boolean;
  stationOnly?: boolean;
};

const NETWORK_MAIN_NAV: NavItem[] = [
  { to: '/', labelKey: 'dashboard' },
  { to: '/stations', labelKey: 'stations', permission: Permissions.STATIONS_MANAGE, networkOnly: true },
  { to: '/agents', labelKey: 'agents', permission: Permissions.STATIONS_MANAGE, networkOnly: true },
  { to: '/users', labelKey: 'users', permission: Permissions.USERS_VIEW, networkOnly: true },
  { to: '/service-tokens', labelKey: 'serviceTokens', superuser: true, networkOnly: true },
  { to: '/tariff-profiles', labelKey: 'tariffProfiles', permission: Permissions.SETTINGS_MANAGE_TARIFFS, networkOnly: true },
  { to: '/audit', labelKey: 'audit', permission: Permissions.USERS_VIEW, networkOnly: true },
  { to: '/reports', labelKey: 'reports', permission: Permissions.REPORTS_VIEW_NETWORK, networkOnly: true },
];

const NETWORK_REFERENCE_NAV: NavItem[] = [
  { to: '/points', labelKey: 'points', permission: Permissions.SETTINGS_MANAGE_TARIFFS, networkOnly: true },
  { to: '/nomenclature', labelKey: 'nomenclature', permission: Permissions.SETTINGS_MANAGE_TARIFFS, networkOnly: true },
  { to: '/policies', labelKey: 'refundPolicies', permission: Permissions.SETTINGS_MANAGE_TARIFFS, networkOnly: true },
];

const STATION_MAIN_NAV: NavItem[] = [
  { to: '/', labelKey: 'dashboard', stationOnly: true },
  { to: '/agents', labelKey: 'agents', permission: Permissions.SCHEDULE_VIEW, stationOnly: true },
  { to: '/trips', labelKey: 'trips', permission: Permissions.SCHEDULE_VIEW, stationOnly: true },
  { to: '/dispatcher', labelKey: 'dispatcher', permission: Permissions.SCHEDULE_VIEW, stationOnly: true },
  { to: '/announcements', labelKey: 'announcements', permission: Permissions.ANNOUNCEMENTS_MANAGE, stationOnly: true },
  { to: '/users', labelKey: 'users', permission: Permissions.USERS_VIEW, stationOnly: true },
  { to: '/settings', labelKey: 'settings', stationOnly: true },
];

function NavLinkItem({ item }: { item: NavItem }) {
  const { t } = useTranslation('nav');
  return (
    <NavLink
      to={item.to}
      end={item.to === '/'}
      className={({ isActive }) =>
        [
          'rounded-lg px-3 py-2 text-sm transition-colors',
          isActive ? 'bg-accent text-accent-foreground font-medium' : 'text-foreground hover:bg-default',
        ].join(' ')
      }
    >
      {t(item.labelKey)}
    </NavLink>
  );
}

function filterNav(items: NavItem[]): NavItem[] {
  return items.filter((item) => {
    if (isNetworkTier()) return item.networkOnly !== false && !item.stationOnly;
    if (isStationTier()) return item.stationOnly !== false && !item.networkOnly;
    return true;
  });
}

function SidebarNav() {
  const { t } = useTranslation('nav');
  const network = isNetworkTier();
  const mainNav = filterNav(network ? NETWORK_MAIN_NAV : STATION_MAIN_NAV);
  const referenceNav = network ? filterNav(NETWORK_REFERENCE_NAV) : [];

  return (
    <nav className="flex flex-col gap-1 p-3">
      {mainNav.map((item) => (
        <PermissionGate key={item.to} permission={item.permission} superuser={item.superuser}>
          <NavLinkItem item={item} />
        </PermissionGate>
      ))}
      {referenceNav.length > 0 ? (
        <>
          <div className="mt-3 px-3 text-xs font-semibold uppercase tracking-widest text-muted">
            {t('reference')}
          </div>
          {referenceNav.map((item) => (
            <PermissionGate key={item.to} permission={item.permission} superuser={item.superuser}>
              <NavLinkItem item={item} />
            </PermissionGate>
          ))}
        </>
      ) : null}
    </nav>
  );
}

export function AdminLayout() {
  const { user, logoutUser } = useAuth();
  const { t } = useTranslation(['nav', 'common']);

  return (
    <div className="flex min-h-screen bg-background text-foreground">
      <aside className="flex w-64 shrink-0 flex-col border-r border-border bg-surface">
        <div className="border-b border-border p-4">
          <div className="text-lg font-semibold tracking-tight">{t('common:appName')}</div>
          <div className="text-xs uppercase tracking-widest text-muted">{t('common:appSubtitle')}</div>
        </div>
        <SidebarNav />
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between gap-4 border-b border-border px-6 py-3">
          <div className="flex min-w-0 flex-1 items-center gap-4">
            {isStationTier() ? <StationSwitcher /> : null}
            <div className="min-w-0">
              <div className="font-medium">{user?.fullName}</div>
              <div className="font-mono text-sm text-muted">{user?.login}</div>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <LocaleThemeToolbar />
            {user?.isSuperuser ? <Chip size="sm">{t('nav:superuser')}</Chip> : null}
            <Button variant="secondary" onPress={() => void logoutUser()}>
              {t('nav:logout')}
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
