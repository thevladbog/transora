import { createBrowserRouter, Navigate } from 'react-router';
import { AdminLayout } from '@/components/layout/AdminLayout';
import { LoginPage } from '@/features/auth/LoginPage';
import { ProtectedRoute } from '@/features/auth/ProtectedRoute';
import { NetworkTierGuard, StationTierGuard } from '@/features/auth/TierGuard';
import { AgentsPage } from '@/features/agents/AgentsPage';
import { AnnouncementsPage } from '@/features/announcements/AnnouncementsPage';
import { DashboardPage } from '@/features/dashboard/DashboardPage';
import { DispatcherPage } from '@/features/dispatcher/DispatcherPage';
import { NomenclatureListPage } from '@/features/nomenclature/NomenclatureListPage';
import { PlaceholderPage } from '@/features/placeholder/PlaceholderPage';
import { PointsListPage } from '@/features/points/PointsListPage';
import { RefundPoliciesListPage } from '@/features/refund-policies/RefundPoliciesListPage';
import { StationSettingsPage } from '@/features/settings/StationSettingsPage';
import { StationsListPage } from '@/features/stations/StationsListPage';
import { RouteEditorPage } from '@/features/routes/RouteEditorPage';
import { RoutesListPage } from '@/features/routes/RoutesListPage';
import { ScheduleEditorPage } from '@/features/schedules/ScheduleEditorPage';
import { SchedulesListPage } from '@/features/schedules/SchedulesListPage';
import { TariffProfileEditorPage } from '@/features/tariff-profiles/TariffProfileEditorPage';
import { TripsListPage } from '@/features/trips/TripsListPage';
import { UserDetailPage } from '@/features/users/UserDetailPage';
import { UsersListPage } from '@/features/users/UsersListPage';
import { isNetworkTier } from '@/lib/app-tier';

const networkOnly = isNetworkTier();

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    element: <ProtectedRoute />,
    children: [
      {
        element: <AdminLayout />,
        children: [
          { index: true, element: <DashboardPage /> },
          ...(networkOnly
            ? [
                {
                  element: <NetworkTierGuard />,
                  children: [
                    { path: 'stations', element: <StationsListPage /> },
                    { path: 'points', element: <PointsListPage /> },
                    { path: 'nomenclature', element: <NomenclatureListPage /> },
                    { path: 'policies', element: <RefundPoliciesListPage /> },
                    { path: 'routes', element: <RoutesListPage /> },
                    { path: 'routes/:routeId', element: <RouteEditorPage /> },
                    { path: 'schedules', element: <SchedulesListPage /> },
                    { path: 'schedules/:scheduleId', element: <ScheduleEditorPage /> },
                    { path: 'tariff-profiles', element: <Navigate to="/routes" replace /> },
                    { path: 'tariff-profiles/:profileId', element: <TariffProfileEditorPage /> },
                    { path: 'agents', element: <AgentsPage /> },
                    { path: 'users', element: <UsersListPage /> },
                    { path: 'users/:userId', element: <UserDetailPage /> },
                    { path: 'service-tokens', element: <PlaceholderPage /> },
                    { path: 'audit', element: <PlaceholderPage /> },
                    { path: 'reports', element: <PlaceholderPage /> },
                    { path: 'refund-policies', element: <Navigate to="/policies" replace /> },
                    { path: 'tariffs', element: <Navigate to="/routes" replace /> },
                  ],
                },
              ]
            : [
                {
                  element: <StationTierGuard />,
                  children: [
                    { path: 'agents', element: <AgentsPage /> },
                    { path: 'trips', element: <TripsListPage /> },
                    { path: 'dispatcher', element: <DispatcherPage /> },
                    { path: 'announcements', element: <AnnouncementsPage /> },
                    { path: 'users', element: <UsersListPage /> },
                    { path: 'users/:userId', element: <UserDetailPage /> },
                    { path: 'settings', element: <StationSettingsPage /> },
                  ],
                },
              ]),
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
]);
