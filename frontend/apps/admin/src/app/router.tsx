import { createBrowserRouter, Navigate } from 'react-router';
import { AdminLayout } from '../components/layout/AdminLayout';
import { LoginPage } from '../features/auth/LoginPage';
import { ProtectedRoute } from '../features/auth/ProtectedRoute';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { PlaceholderPage } from '../features/placeholder/PlaceholderPage';

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
          { path: 'users', element: <PlaceholderPage /> },
          { path: 'service-tokens', element: <PlaceholderPage /> },
          { path: 'tariffs', element: <PlaceholderPage /> },
          { path: 'refund-policies', element: <PlaceholderPage /> },
          { path: 'audit', element: <PlaceholderPage /> },
          { path: 'reports', element: <PlaceholderPage /> },
        ],
      },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
]);
