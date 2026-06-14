import { customInstance } from './mutator';

export interface AdminStationResponse {
  id: string;
  code: string;
  name: string;
  city: string;
  timezone: string;
  address?: string;
  pointId?: string;
  description?: string;
  contactPhone?: string;
  isActive: boolean;
  agentConnected: boolean;
  agentLastSeenAt?: string;
  agentVersion?: string;
}

export interface StationPointInput {
  latitude: number;
  longitude: number;
}

export interface CreateAdminStationRequest {
  code?: string;
  name: string;
  city: string;
  timezone?: string;
  address?: string;
  pointId?: string;
  point?: StationPointInput;
  description?: string;
  contactPhone?: string;
}

export interface UpdateAdminStationRequest {
  name?: string;
  city?: string;
  timezone?: string;
  address?: string;
  pointId?: string;
  point?: StationPointInput;
  description?: string;
  contactPhone?: string;
  isActive?: boolean;
}

export interface ProvisioningTokenResponse {
  code: string;
  expiresAt: string;
}

export interface StationAgentStatusResponse {
  stationId: string;
  connected: boolean;
  lastSeenAt?: string;
  agentVersion?: string;
}

export function listAdminStations() {
  return customInstance<{ data: AdminStationResponse[] }>('/api/admin/stations', { method: 'GET' });
}

export function getAdminStation(stationId: string) {
  return customInstance<{ data: AdminStationResponse }>(`/api/admin/stations/${stationId}`, {
    method: 'GET',
  });
}

export function createAdminStation(payload: CreateAdminStationRequest) {
  return customInstance<{ data: AdminStationResponse }>('/api/admin/stations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function updateAdminStation(stationId: string, payload: UpdateAdminStationRequest) {
  return customInstance<{ data: AdminStationResponse }>(`/api/admin/stations/${stationId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export function createStationProvisioningToken(stationId: string) {
  return customInstance<{ data: ProvisioningTokenResponse }>(
    `/api/admin/stations/${stationId}/provisioning-token`,
    { method: 'POST' },
  );
}

export function getStationAgentStatus(stationId: string) {
  return customInstance<{ data: StationAgentStatusResponse }>(
    `/api/admin/stations/${stationId}/status`,
    { method: 'GET' },
  );
}

export function forceStationAgentSync(stationId: string) {
  return customInstance<{ data: undefined }>(`/api/stations/${stationId}/agent/sync-force`, {
    method: 'POST',
  });
}

export interface AdminDisplayBoardResponse {
  id: string;
  stationCode: string;
  boardType: string;
  platformNumber?: string;
  name: string;
  isActive: boolean;
  agentId?: string;
  lastSeenAt?: string;
}

export function listStationDisplayBoards(stationId: string) {
  return customInstance<{ data: AdminDisplayBoardResponse[] }>(
    `/api/admin/stations/${stationId}/display-boards`,
    { method: 'GET' },
  );
}
