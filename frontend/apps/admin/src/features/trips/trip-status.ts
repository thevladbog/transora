import { UpdateTripRequestStatus } from '@transora/api-client';

const ALLOWED: Record<string, UpdateTripRequestStatus[]> = {
  PLANNED: [UpdateTripRequestStatus.OPEN, UpdateTripRequestStatus.CANCELLED],
  OPEN: [
    UpdateTripRequestStatus.BOARDING,
    UpdateTripRequestStatus.DEPARTED,
    UpdateTripRequestStatus.CANCELLED,
  ],
  BOARDING: [UpdateTripRequestStatus.DEPARTED, UpdateTripRequestStatus.CANCELLED],
  DEPARTED: [UpdateTripRequestStatus.IN_TRANSIT, UpdateTripRequestStatus.ARRIVED],
  IN_TRANSIT: [UpdateTripRequestStatus.ARRIVED],
  ARRIVED: [UpdateTripRequestStatus.COMPLETED],
  COMPLETED: [],
  CANCELLED: [],
};

export function getAllowedNextStatuses(current: string): UpdateTripRequestStatus[] {
  const currentStatus = current as UpdateTripRequestStatus;
  const next = ALLOWED[current] ?? [];
  return [...new Set([currentStatus, ...next])];
}

export function isResourcesLocked(status: string): boolean {
  return ['DEPARTED', 'IN_TRANSIT', 'ARRIVED', 'COMPLETED'].includes(status);
}

export function isTerminal(status: string): boolean {
  return status === 'CANCELLED' || status === 'COMPLETED';
}
