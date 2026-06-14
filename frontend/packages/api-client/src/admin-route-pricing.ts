import { customInstance } from './mutator';
import type { TariffCellRequest, TariffCellResponse } from './admin-catalog';

type ApiResponse<T> = { data: T };

export interface RoutePricingSummaryResponse {
  routeId: string;
  carrierId: string;
  carrierName?: string;
  code?: string;
  routeNumber: string;
  name: string;
  isActive: boolean;
  tariffProfileId?: string;
  stopCount: number;
  matrixCellCount: number;
}

export interface RouteLegDistanceResponse {
  fromStopOrder: number;
  toStopOrder: number;
  distanceKm: number;
  durationMin?: number;
}

export interface RoutePricingStopResponse {
  stopOrder: number;
  pointId: string;
  pointCode?: string;
  pointName?: string;
  pointCity?: string;
  stationId?: string;
  stationCode?: string;
  isBranch: boolean;
  scheduledDurationMin?: number;
}

export interface RoutePricingBundleResponse {
  routeId: string;
  carrierId: string;
  code?: string;
  routeNumber: string;
  name: string;
  description?: string;
  isActive: boolean;
  tariffProfileId: string;
  validFrom?: string;
  validTo?: string;
  stops: RoutePricingStopResponse[];
  cells: TariffCellResponse[];
  distanceKm?: number;
  distanceSource?: 'ROAD' | 'STRAIGHT_LINE';
  durationMin?: number;
  legs: RouteLegDistanceResponse[];
}

export interface CreateRoutePricingRequest {
  carrierId: string;
  code: string;
  routeNumber?: string;
  name: string;
  description?: string;
}

export interface UpdateRoutePricingRequest {
  carrierId?: string;
  code?: string;
  routeNumber?: string;
  name?: string;
  description?: string;
  isActive?: boolean;
  validFrom?: string;
  validTo?: string;
}

export interface SyncRouteStopsRequest {
  pointIds: string[];
  legDurationsMin?: number[];
}

export interface UpsertRouteMatrixRequest {
  cells: TariffCellRequest[];
}

export const listRoutePricing = () =>
  customInstance<ApiResponse<RoutePricingSummaryResponse[]>>('/api/admin/route-pricing', { method: 'GET' });

export const getRoutePricing = (routeId: string) =>
  customInstance<ApiResponse<RoutePricingBundleResponse>>(`/api/admin/route-pricing/${routeId}`, { method: 'GET' });

export const createRoutePricing = (payload: CreateRoutePricingRequest) =>
  customInstance<ApiResponse<RoutePricingBundleResponse>>('/api/admin/route-pricing', {
    method: 'POST',
    body: JSON.stringify(payload),
  });

export const updateRoutePricing = (routeId: string, payload: UpdateRoutePricingRequest) =>
  customInstance<ApiResponse<RoutePricingBundleResponse>>(`/api/admin/route-pricing/${routeId}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });

export const syncRouteStops = (routeId: string, payload: SyncRouteStopsRequest) =>
  customInstance<ApiResponse<RoutePricingBundleResponse>>(`/api/admin/route-pricing/${routeId}/stops`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });

export const upsertRouteMatrix = (routeId: string, payload: UpsertRouteMatrixRequest) =>
  customInstance<ApiResponse<RoutePricingBundleResponse>>(`/api/admin/route-pricing/${routeId}/matrix`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });

export const deactivateRoutePricing = (routeId: string) =>
  customInstance<ApiResponse<RoutePricingBundleResponse>>(`/api/admin/route-pricing/${routeId}`, {
    method: 'DELETE',
  });

export type { TariffCellRequest };
