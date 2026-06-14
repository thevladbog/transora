import type { ReactNode } from 'react';
import { useAuth } from '@/features/auth/auth-context';
import { Permissions, type Permission } from '@/lib/permissions';

type PermissionGateProps = {
  permission?: Permission;
  superuser?: boolean;
  children: ReactNode;
  fallback?: ReactNode;
};

export function PermissionGate({
  permission,
  superuser = false,
  children,
  fallback = null,
}: PermissionGateProps) {
  const { user } = useAuth();

  if (!user) {
    return fallback;
  }

  if (superuser && user.isSuperuser) {
    return children;
  }

  if (permission && user.permissions.includes(permission)) {
    return children;
  }

  if (!permission && !superuser) {
    return children;
  }

  return fallback;
}

export function useCanAccess(permission?: Permission, superuser = false): boolean {
  const { user } = useAuth();
  if (!user) {
    return false;
  }
  if (superuser) {
    return user.isSuperuser;
  }
  if (permission) {
    return user.permissions.includes(permission);
  }
  return true;
}

export { Permissions };
