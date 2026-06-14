export const Permissions = {
  TICKETS_SELL: 'tickets:sell',
  TICKETS_REFUND: 'tickets:refund',
  TICKETS_VIEW: 'tickets:view',
  SHIFTS_MANAGE: 'shifts:manage',
  SCHEDULE_VIEW: 'schedule:view',
  SCHEDULE_CREATE: 'schedule:create',
  SCHEDULE_EDIT: 'schedule:edit',
  SCHEDULE_CANCEL_TRIP: 'schedule:cancel_trip',
  INVENTORY_VIEW: 'inventory:view',
  INVENTORY_TOGGLE: 'inventory:toggle_restriction',
  INVENTORY_MANUAL_BLOCK: 'inventory:manual_block',
  INVENTORY_TRANSIT_GATE: 'inventory:open_transit_gate',
  INVENTORY_CLOSE_TRANSIT_GATE: 'inventory:close_transit_gate',
  DOCUMENTS_PRINT: 'documents:print',
  DOCUMENTS_VIEW_MANIFEST: 'documents:view_manifest',
  BOARDING_SCAN: 'boarding:scan',
  BOARDING_VIEW_STATS: 'boarding:view_stats',
  ANNOUNCEMENTS_MANAGE: 'announcements:manage_queue',
  ANNOUNCEMENTS_PLAY_AUDIO: 'announcements:play_audio',
  USERS_VIEW: 'users:view',
  USERS_CREATE: 'users:create',
  USERS_EDIT: 'users:edit',
  USERS_DEACTIVATE: 'users:deactivate',
  REPORTS_VIEW_STATION: 'reports:view_station',
  REPORTS_VIEW_NETWORK: 'reports:view_network',
  STATIONS_MANAGE: 'stations:manage',
  SETTINGS_MANAGE_TARIFFS: 'settings:manage_tariffs',
} as const;

export type Permission = (typeof Permissions)[keyof typeof Permissions];

export function hasPermission(
  permissions: Set<string> | string[],
  permission: Permission,
): boolean {
  const set = permissions instanceof Set ? permissions : new Set(permissions);
  return set.has(permission);
}
