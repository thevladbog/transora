import { customInstance } from './mutator';

export interface PointResponse {
  id: string;
  code: string;
  name: string;
  city: string;
  address?: string;
  latitude: number;
  longitude: number;
  timezone: string;
  isActive: boolean;
  createdAt: string;
}

export interface UpsertPointRequest {
  code?: string;
  name: string;
  city?: string;
  address?: string;
  latitude: number;
  longitude: number;
  timezone?: string;
  isActive?: boolean;
}

export interface GeocodeResult {
  displayName: string;
  latitude: number;
  longitude: number;
  city: string;
  address?: string;
}

export interface NomenclatureResponse {
  id: string;
  code: string;
  name: string;
  category: string;
  priceCents: number;
  refundPolicyId?: string;
  refundPolicyName?: string;
  isActive: boolean;
  description?: string;
  createdAt: string;
  saleMode: string;
  pricingMode: string;
  routePercent?: number;
  minPriceCents?: number;
  maxPriceCents?: number;
  maxQtyPerTicket: number;
  refundAllowed: boolean;
  printName: string;
  ffdPaymentObject: number;
  ffdPaymentMethod: number;
  ffdVatTag: number;
  ffdMeasureCode: number;
}

export interface UpsertNomenclatureRequest {
  code: string;
  name: string;
  category: string;
  priceCents: number;
  refundPolicyId?: string;
  isActive?: boolean;
  description?: string;
  saleMode?: string;
  pricingMode?: string;
  routePercent?: number;
  minPriceCents?: number;
  maxPriceCents?: number;
  maxQtyPerTicket?: number;
  refundAllowed?: boolean;
  printName?: string;
  ffdPaymentObject?: number;
  ffdPaymentMethod?: number;
  ffdVatTag?: number;
  ffdMeasureCode?: number;
}

export interface TariffProfileResponse {
  id: string;
  name: string;
  routeId?: string;
  validFrom?: string;
  validTo?: string;
  refundPolicyId?: string;
  refundPolicyName?: string;
  isActive: boolean;
  stopCount: number;
  createdAt: string;
}

export interface UpsertTariffProfileRequest {
  name: string;
  routeId?: string;
  validFrom?: string;
  validTo?: string;
  refundPolicyId?: string;
  isActive?: boolean;
}

export interface TariffProfileStopResponse {
  id: string;
  pointId: string;
  stopOrder: number;
  pointCode?: string;
  pointName?: string;
  pointCity?: string;
}

export interface TariffCellResponse {
  id: string;
  fromStopOrder: number;
  toStopOrder: number;
  priceCents: number;
  isMirrorOverride: boolean;
}

export interface TariffMatrixResponse {
  profile: TariffProfileResponse;
  stops: TariffProfileStopResponse[];
  cells: TariffCellResponse[];
}

export interface TariffCellRequest {
  fromStopOrder: number;
  toStopOrder: number;
  priceCents: number;
  isMirrorOverride?: boolean;
}

type ApiResponse<T> = { data: T; status: number; headers: Headers };

const jsonHeaders = { 'Content-Type': 'application/json' };

export const listPoints = () =>
  customInstance<ApiResponse<PointResponse[]>>('/api/admin/points', { method: 'GET' });

export const getPoint = (id: string) =>
  customInstance<ApiResponse<PointResponse>>(`/api/admin/points/${id}`, { method: 'GET' });

export const createPoint = (body: UpsertPointRequest) =>
  customInstance<ApiResponse<PointResponse>>('/api/admin/points', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(body),
  });

export const updatePoint = (id: string, body: UpsertPointRequest) =>
  customInstance<ApiResponse<PointResponse>>(`/api/admin/points/${id}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(body),
  });

export const deletePoint = (id: string) =>
  customInstance<ApiResponse<void>>(`/api/admin/points/${id}`, { method: 'DELETE' });

export const geocodeSearch = (q: string, limit = 10, city?: string) => {
  const params = new URLSearchParams({ q, limit: String(limit) });
  if (city?.trim()) {
    params.set('city', city.trim());
  }
  return customInstance<ApiResponse<GeocodeResult[]>>(`/api/admin/geocode/search?${params.toString()}`, {
    method: 'GET',
  });
};

export const geocodeReverse = (lat: number, lon: number) =>
  customInstance<ApiResponse<GeocodeResult | null>>(
    `/api/admin/geocode/reverse?lat=${lat}&lon=${lon}`,
    { method: 'GET' },
  );

export const listNomenclature = () =>
  customInstance<ApiResponse<NomenclatureResponse[]>>('/api/admin/nomenclature', { method: 'GET' });

export const createNomenclature = (body: UpsertNomenclatureRequest) =>
  customInstance<ApiResponse<NomenclatureResponse>>('/api/admin/nomenclature', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(body),
  });

export const updateNomenclature = (id: string, body: UpsertNomenclatureRequest) =>
  customInstance<ApiResponse<NomenclatureResponse>>(`/api/admin/nomenclature/${id}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(body),
  });

export const deleteNomenclature = (id: string) =>
  customInstance<ApiResponse<void>>(`/api/admin/nomenclature/${id}`, { method: 'DELETE' });

export const listTariffProfiles = () =>
  customInstance<ApiResponse<TariffProfileResponse[]>>('/api/admin/tariff-profiles', { method: 'GET' });

export const getTariffProfile = (id: string) =>
  customInstance<ApiResponse<TariffProfileResponse>>(`/api/admin/tariff-profiles/${id}`, {
    method: 'GET',
  });

export const getTariffMatrix = (id: string) =>
  customInstance<ApiResponse<TariffMatrixResponse>>(`/api/admin/tariff-profiles/${id}/matrix`, {
    method: 'GET',
  });

export const createTariffProfile = (body: UpsertTariffProfileRequest) =>
  customInstance<ApiResponse<TariffProfileResponse>>('/api/admin/tariff-profiles', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(body),
  });

export const updateTariffProfile = (id: string, body: UpsertTariffProfileRequest) =>
  customInstance<ApiResponse<TariffProfileResponse>>(`/api/admin/tariff-profiles/${id}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(body),
  });

export const replaceTariffStops = (id: string, pointIds: string[]) =>
  customInstance<ApiResponse<TariffMatrixResponse>>(`/api/admin/tariff-profiles/${id}/stops`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify({ pointIds }),
  });

export const upsertTariffMatrix = (id: string, cells: TariffCellRequest[]) =>
  customInstance<ApiResponse<TariffMatrixResponse>>(`/api/admin/tariff-profiles/${id}/matrix`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify({ cells }),
  });

export const deleteTariffProfile = (id: string) =>
  customInstance<ApiResponse<void>>(`/api/admin/tariff-profiles/${id}`, { method: 'DELETE' });

export interface CommercePolicyTierRequest {
  hoursBeforeMin?: number;
  hoursBeforeMax?: number;
  penaltyPercent: number;
  refundAllowed?: boolean;
  sortOrder?: number;
  fixedPriceCents?: number;
  percentValue?: number;
}

export interface UpsertCommercePolicyRequest {
  name: string;
  isActive?: boolean;
  serviceFeeCents?: number;
  policyType?: string;
  nomenclatureItemId?: string;
  isMandatory?: boolean;
  pricingMode?: string;
  fixedPriceCents?: number;
  percentValue?: number;
  percentBasis?: string;
  minPriceCents?: number;
  maxPriceCents?: number;
  tiers?: CommercePolicyTierRequest[];
}

export interface CommercePolicyTierResponse {
  id: string;
  hoursBeforeMin?: number;
  hoursBeforeMax?: number;
  penaltyPercent: number;
  refundAllowed: boolean;
  sortOrder: number;
  fixedPriceCents?: number;
  percentValue?: number;
}

export interface CommercePolicyResponse {
  id: string;
  name: string;
  isActive: boolean;
  serviceFeeCents: number;
  createdAt: string;
  policyType: string;
  nomenclatureItemId?: string;
  nomenclatureCode?: string;
  nomenclatureName?: string;
  isMandatory: boolean;
  pricingMode: string;
  fixedPriceCents?: number;
  percentValue?: number;
  percentBasis?: string;
  minPriceCents?: number;
  maxPriceCents?: number;
  tiers: CommercePolicyTierResponse[];
}

export interface RoutePolicyBindingRequest {
  policyId: string;
  priority: number;
}

export interface RoutePolicyEntry {
  policyId: string;
  priority: number;
  policyName: string;
  policyType: string;
  policyActive: boolean;
}

export interface RoutePoliciesResponse {
  policies: RoutePolicyEntry[];
}

export const listCommercePolicies = (policyType?: string) => {
  const query = policyType ? `?policyType=${encodeURIComponent(policyType)}` : '';
  return customInstance<ApiResponse<CommercePolicyResponse[]>>(`/api/admin/policies${query}`, { method: 'GET' });
};

export const createCommercePolicy = (body: UpsertCommercePolicyRequest) =>
  customInstance<ApiResponse<CommercePolicyResponse>>('/api/admin/policies', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(body),
  });

export const updateCommercePolicy = (id: string, body: UpsertCommercePolicyRequest) =>
  customInstance<ApiResponse<CommercePolicyResponse>>(`/api/admin/policies/${id}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(body),
  });

export const deleteCommercePolicy = (id: string) =>
  customInstance<ApiResponse<void>>(`/api/admin/policies/${id}`, { method: 'DELETE' });

export const getRoutePolicies = (routeId: string) =>
  customInstance<ApiResponse<RoutePoliciesResponse>>(`/api/admin/routes/${routeId}/policies`, { method: 'GET' });

export const replaceRoutePolicies = (routeId: string, policies: RoutePolicyBindingRequest[]) =>
  customInstance<ApiResponse<RoutePoliciesResponse>>(`/api/admin/routes/${routeId}/policies`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify({ policies }),
  });
