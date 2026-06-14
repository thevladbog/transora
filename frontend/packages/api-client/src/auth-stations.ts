import { customInstance } from './mutator';

export interface UserStationResponse {
  stationId: string;
  code: string;
  name: string;
  city: string;
}

export interface SwitchStationRequest {
  stationId: string | null;
  refreshToken?: string;
}

export interface SwitchStationTokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
}

export function meStations() {
  return customInstance<{ data: UserStationResponse[] }>('/auth/me/stations', { method: 'GET' });
}

export function switchStation(payload: SwitchStationRequest) {
  return customInstance<{ data: SwitchStationTokenResponse }>('/auth/switch-station', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}
