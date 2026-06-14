export type AppTier = 'network' | 'station';

const tier = import.meta.env.VITE_APP_TIER as AppTier | undefined;

export function getAppTier(): AppTier {
  return tier === 'station' ? 'station' : 'network';
}

export function isNetworkTier(): boolean {
  return getAppTier() === 'network';
}

export function isStationTier(): boolean {
  return getAppTier() === 'station';
}
